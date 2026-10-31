interface Env {
  TRAFFIC_CACHE: DurableObjectNamespace;
  SEOUL_SUBWAY_API_KEY: string;
}

interface CacheEntry {
  body: string;
  fetchedAt: number;
  freshUntil: number;
  staleUntil: number;
}

interface DailyBudget {
  date: string;
  count: number;
}

const FRESH_MS = 20_000;
const STALE_MS = 5 * 60_000;
const DAILY_UPSTREAM_LIMIT = 900;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "GET") {
      return jsonError("method_not_allowed", 405);
    }
    if (url.pathname === "/health") {
      return Response.json({ ok: true, service: "nomi-traffic-proxy" });
    }
    if (url.pathname !== "/v1/subway/arrivals") {
      return jsonError("not_found", 404);
    }

    const station = normalizeStation(url.searchParams.get("station"));
    if (!station) {
      return jsonError("invalid_station", 400);
    }

    // One global Durable Object coalesces requests across users and locations.
    // This prevents 1,000 phones at one station from consuming 1,000 API calls.
    const id = env.TRAFFIC_CACHE.idFromName("global");
    const stub = env.TRAFFIC_CACHE.get(id);
    const internal = new URL("https://traffic-cache.internal/arrivals");
    internal.searchParams.set("station", station);
    return stub.fetch(new Request(internal));
  },
} satisfies ExportedHandler<Env>;

export class TrafficCache implements DurableObject {
  private readonly memory = new Map<string, CacheEntry>();
  private readonly inFlight = new Map<string, Promise<CacheEntry>>();

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const station = normalizeStation(new URL(request.url).searchParams.get("station"));
    if (!station) return jsonError("invalid_station", 400);

    const now = Date.now();
    const entry = await this.getEntry(station);
    if (entry && entry.freshUntil > now) {
      return cachedResponse(entry, "HIT");
    }

    try {
      const fresh = await this.refreshOnce(station);
      return cachedResponse(fresh, entry ? "REFRESH" : "MISS");
    } catch (error) {
      if (entry && entry.staleUntil > now) {
        return cachedResponse(entry, "STALE");
      }
      const reason = error instanceof Error ? error.message : "upstream_failed";
      return jsonError(reason, reason === "daily_budget_exhausted" ? 429 : 503);
    }
  }

  private async getEntry(station: string): Promise<CacheEntry | undefined> {
    const memory = this.memory.get(station);
    if (memory) return memory;
    const stored = await this.state.storage.get<CacheEntry>(`station:${station}`);
    if (stored) this.memory.set(station, stored);
    return stored;
  }

  private refreshOnce(station: string): Promise<CacheEntry> {
    const existing = this.inFlight.get(station);
    if (existing) return existing;

    const promise = this.refresh(station).finally(() => {
      this.inFlight.delete(station);
    });
    this.inFlight.set(station, promise);
    return promise;
  }

  private async refresh(station: string): Promise<CacheEntry> {
    if (!this.env.SEOUL_SUBWAY_API_KEY?.trim()) {
      throw new Error("server_key_missing");
    }
    if (!(await this.takeDailyBudget())) {
      throw new Error("daily_budget_exhausted");
    }

    const key = encodeURIComponent(this.env.SEOUL_SUBWAY_API_KEY.trim());
    const name = encodeURIComponent(station);
    const upstream =
      `http://swopenAPI.seoul.go.kr/api/subway/${key}/json/` +
      `realtimeStationArrival/0/30/${name}`;
    const response = await fetch(upstream, {
      headers: { "User-Agent": "nomi-traffic-proxy/1.0" },
    });
    const body = await response.text();
    if (!response.ok || !isSuccessfulSeoulResponse(body)) {
      throw new Error("upstream_failed");
    }

    const now = Date.now();
    const entry: CacheEntry = {
      body,
      fetchedAt: now,
      freshUntil: now + FRESH_MS,
      staleUntil: now + STALE_MS,
    };
    this.memory.set(station, entry);
    await this.state.storage.put(`station:${station}`, entry);
    return entry;
  }

  private async takeDailyBudget(): Promise<boolean> {
    const today = kstDate();
    return this.state.storage.transaction(async (transaction) => {
      const saved = await transaction.get<DailyBudget>("daily-budget");
      const budget = saved?.date === today ? saved : { date: today, count: 0 };
      if (budget.count >= DAILY_UPSTREAM_LIMIT) return false;
      await transaction.put("daily-budget", {
        date: today,
        count: budget.count + 1,
      } satisfies DailyBudget);
      return true;
    });
  }
}

function cachedResponse(entry: CacheEntry, cache: string): Response {
  const age = Math.max(0, Math.floor((Date.now() - entry.fetchedAt) / 1000));
  return new Response(entry.body, {
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "public, max-age=10",
      "X-Nomi-Cache": cache,
      Age: String(age),
    },
  });
}

function normalizeStation(raw: string | null): string | null {
  if (!raw) return null;
  const station = raw.trim().replace(/역$/, "");
  if (station.length < 1 || station.length > 30) return null;
  // Reject URL/path/control characters while allowing Korean station aliases.
  if (!/^[가-힣A-Za-z0-9()·.\-\s]+$/.test(station)) return null;
  return station;
}

function isSuccessfulSeoulResponse(body: string): boolean {
  try {
    const parsed = JSON.parse(body) as {
      errorMessage?: { code?: string };
      code?: string;
    };
    const code = parsed.errorMessage?.code ?? parsed.code ?? "";
    return code === "INFO-000";
  } catch {
    return false;
  }
}

function kstDate(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function jsonError(error: string, status: number): Response {
  return Response.json({ ok: false, error }, { status });
}
