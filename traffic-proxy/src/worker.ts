interface Env {
  TRAFFIC_CACHE: DurableObjectNamespace;
  SEOUL_SUBWAY_API_KEY: string;
  SEOUL_BUS_API_KEY: string;
  GYEONGGI_BUS_API_KEY: string;
  TAGO_BUS_API_KEY: string;
}

type Source = "subway" | "seoul-bus" | "gyeonggi-bus" | "tago-bus";

interface CacheEntry {
  body: string;
  contentType: string;
  fetchedAt: number;
  freshUntil: number;
  staleUntil: number;
}

interface DailyBudget {
  date: string;
  count: number;
}

interface UpstreamSpec {
  source: Source;
  cacheKey: string;
  url: string;
  contentType: string;
}

const FRESH_MS = 20_000;
const STALE_MS = 5 * 60_000;
// Conservative per-provider guard. Raise only after the provider approves more.
const DAILY_UPSTREAM_LIMIT = 900;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "GET") return jsonError("method_not_allowed", 405);
    if (url.pathname === "/health") {
      return Response.json({
        ok: true,
        service: "nomi-traffic-proxy",
        endpoints: ["subway", "seoul-bus", "gyeonggi-bus", "tago-bus"],
      });
    }

    const internal = publicToInternal(url);
    if (!internal) return jsonError("invalid_request", 400);

    const id = env.TRAFFIC_CACHE.idFromName("global");
    return env.TRAFFIC_CACHE.get(id).fetch(new Request(internal));
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
    const spec = upstreamSpec(new URL(request.url), this.env);
    if (!spec) return jsonError("server_key_missing_or_invalid_request", 503);

    const now = Date.now();
    const entry = await this.getEntry(spec.cacheKey);
    if (entry && entry.freshUntil > now) return cachedResponse(entry, "HIT");

    try {
      const fresh = await this.refreshOnce(spec);
      return cachedResponse(fresh, entry ? "REFRESH" : "MISS");
    } catch (error) {
      if (entry && entry.staleUntil > now) return cachedResponse(entry, "STALE");
      const reason = error instanceof Error ? error.message : "upstream_failed";
      return jsonError(reason, reason === "daily_budget_exhausted" ? 429 : 503);
    }
  }

  private async getEntry(key: string): Promise<CacheEntry | undefined> {
    const memory = this.memory.get(key);
    if (memory) return memory;
    const stored = await this.state.storage.get<CacheEntry>(`cache:${key}`);
    if (stored) this.memory.set(key, stored);
    return stored;
  }

  private refreshOnce(spec: UpstreamSpec): Promise<CacheEntry> {
    const existing = this.inFlight.get(spec.cacheKey);
    if (existing) return existing;
    const promise = this.refresh(spec).finally(() => this.inFlight.delete(spec.cacheKey));
    this.inFlight.set(spec.cacheKey, promise);
    return promise;
  }

  private async refresh(spec: UpstreamSpec): Promise<CacheEntry> {
    if (!(await this.takeDailyBudget(spec.source))) {
      throw new Error("daily_budget_exhausted");
    }
    const response = await fetch(spec.url, {
      headers: { "User-Agent": "nomi-traffic-proxy/1.1" },
    });
    const body = await response.text();
    if (!response.ok || !successful(spec.source, body)) {
      throw new Error("upstream_failed");
    }

    const now = Date.now();
    const entry: CacheEntry = {
      body,
      contentType: spec.contentType,
      fetchedAt: now,
      freshUntil: now + FRESH_MS,
      staleUntil: now + STALE_MS,
    };
    this.memory.set(spec.cacheKey, entry);
    await this.state.storage.put(`cache:${spec.cacheKey}`, entry);
    return entry;
  }

  private async takeDailyBudget(source: Source): Promise<boolean> {
    const today = kstDate();
    const key = `daily-budget:${source}`;
    return this.state.storage.transaction(async (transaction) => {
      const saved = await transaction.get<DailyBudget>(key);
      const budget = saved?.date === today ? saved : { date: today, count: 0 };
      if (budget.count >= DAILY_UPSTREAM_LIMIT) return false;
      await transaction.put(key, { date: today, count: budget.count + 1 } satisfies DailyBudget);
      return true;
    });
  }
}

function publicToInternal(url: URL): URL | null {
  const internal = new URL("https://traffic-cache.internal/fetch");
  if (url.pathname === "/v1/subway/arrivals") {
    const station = normalizeStation(url.searchParams.get("station"));
    if (!station) return null;
    internal.searchParams.set("source", "subway");
    internal.searchParams.set("station", station);
    return internal;
  }
  if (url.pathname === "/v1/bus/seoul/arrivals") {
    const stopNo = normalizeSeoulStop(url.searchParams.get("stopNo"));
    if (!stopNo) return null;
    internal.searchParams.set("source", "seoul-bus");
    internal.searchParams.set("stopNo", stopNo);
    return internal;
  }
  if (url.pathname === "/v1/bus/gyeonggi/arrivals") {
    const stationId = normalizeToken(url.searchParams.get("stationId"), 30)?.replace(/^GGB/i, "");
    if (!stationId) return null;
    internal.searchParams.set("source", "gyeonggi-bus");
    internal.searchParams.set("stationId", stationId);
    return internal;
  }
  if (url.pathname === "/v1/bus/tago/arrivals") {
    const cityCode = normalizeToken(url.searchParams.get("cityCode"), 10);
    const nodeId = normalizeToken(url.searchParams.get("nodeId"), 40);
    if (!cityCode || !nodeId) return null;
    internal.searchParams.set("source", "tago-bus");
    internal.searchParams.set("cityCode", cityCode);
    internal.searchParams.set("nodeId", nodeId);
    return internal;
  }
  return null;
}

function upstreamSpec(url: URL, env: Env): UpstreamSpec | null {
  const source = url.searchParams.get("source") as Source | null;
  if (source === "subway") {
    const station = normalizeStation(url.searchParams.get("station"));
    const key = env.SEOUL_SUBWAY_API_KEY?.trim();
    if (!station || !key) return null;
    return {
      source,
      cacheKey: `${source}:${station}`,
      url:
        `http://swopenAPI.seoul.go.kr/api/subway/${encodeURIComponent(key)}/json/` +
        `realtimeStationArrival/0/30/${encodeURIComponent(station)}`,
      contentType: "application/json; charset=utf-8",
    };
  }
  if (source === "seoul-bus") {
    const stopNo = normalizeSeoulStop(url.searchParams.get("stopNo"));
    const key = env.SEOUL_BUS_API_KEY?.trim();
    if (!stopNo || !key) return null;
    return {
      source,
      cacheKey: `${source}:${stopNo}`,
      url:
        "http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid" +
        `?arsId=${encodeURIComponent(stopNo)}&serviceKey=${key}`,
      contentType: "application/xml; charset=utf-8",
    };
  }
  if (source === "gyeonggi-bus") {
    const stationId = normalizeToken(url.searchParams.get("stationId"), 30);
    const key = env.GYEONGGI_BUS_API_KEY?.trim();
    if (!stationId || !key) return null;
    return {
      source,
      cacheKey: `${source}:${stationId}`,
      url:
        "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2" +
        `?serviceKey=${key}&stationId=${encodeURIComponent(stationId)}&format=json`,
      contentType: "application/json; charset=utf-8",
    };
  }
  if (source === "tago-bus") {
    const cityCode = normalizeToken(url.searchParams.get("cityCode"), 10);
    const nodeId = normalizeToken(url.searchParams.get("nodeId"), 40);
    const key = env.TAGO_BUS_API_KEY?.trim();
    if (!cityCode || !nodeId || !key) return null;
    return {
      source,
      cacheKey: `${source}:${cityCode}:${nodeId}`,
      url:
        "https://apis.data.go.kr/1613000/ArvlInfoInqireService/" +
        "getSttnAcctoArvlPrearngeInfoList" +
        `?serviceKey=${key}&cityCode=${encodeURIComponent(cityCode)}` +
        `&nodeId=${encodeURIComponent(nodeId)}&numOfRows=100&pageNo=1&_type=json`,
      contentType: "application/json; charset=utf-8",
    };
  }
  return null;
}

function successful(source: Source, body: string): boolean {
  if (source === "seoul-bus") {
    return /<headerCd>\s*0\s*<\/headerCd>/.test(body);
  }
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    if (source === "subway") {
      const error = parsed.errorMessage as { code?: string } | undefined;
      return (error?.code ?? parsed.code) === "INFO-000";
    }
    if (source === "gyeonggi-bus") {
      const response = parsed.response as { msgHeader?: { resultCode?: number | string } } | undefined;
      return String(response?.msgHeader?.resultCode ?? "") === "0";
    }
    const response = parsed.response as { header?: { resultCode?: string } } | undefined;
    return response?.header?.resultCode === "00";
  } catch {
    return false;
  }
}

function cachedResponse(entry: CacheEntry, cache: string): Response {
  const age = Math.max(0, Math.floor((Date.now() - entry.fetchedAt) / 1000));
  return new Response(entry.body, {
    headers: {
      "Content-Type": entry.contentType || "application/json; charset=utf-8",
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
  return /^[가-힣A-Za-z0-9()·.\-\s]+$/.test(station) ? station : null;
}

function normalizeSeoulStop(raw: string | null): string | null {
  if (!raw) return null;
  const digits = raw.replace(/\D/g, "");
  if (!digits || digits.length > 5) return null;
  return digits.padStart(5, "0");
}

function normalizeToken(raw: string | null, max: number): string | null {
  if (!raw) return null;
  const value = raw.trim();
  return value.length >= 1 && value.length <= max && /^[A-Za-z0-9_.-]+$/.test(value)
    ? value
    : null;
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
