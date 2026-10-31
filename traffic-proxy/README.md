# Nomi traffic proxy

공공 API 키를 앱에서 분리하고, 모든 사용자가 역별 실시간 결과를 공유하는
Cloudflare Worker입니다.

## 동작

- `GET /v1/subway/arrivals?station=정발산`
- `GET /v1/bus/seoul/arrivals?stopNo=01016`
- `GET /v1/bus/gyeonggi/arrivals?stationId=219000907`
- `GET /v1/bus/tago/arrivals?cityCode=31100&nodeId=GGB219000907`
- 같은 역의 요청은 전 세계에서 하나의 Durable Object로 모음
- 정상 결과는 20초 동안 공유
- API 장애·할당량 소진 시 최대 5분 된 캐시를 반환
- 동시 요청은 한 번의 원본 API 호출로 합침
- 공급처별 원본 호출은 하루 900회에서 차단하여 여유분을 남김

## 최초 배포

```sh
npm install
npx wrangler login
npx wrangler secret put SEOUL_SUBWAY_API_KEY
npx wrangler secret put SEOUL_BUS_API_KEY
npx wrangler secret put GYEONGGI_BUS_API_KEY
npx wrangler secret put TAGO_BUS_API_KEY
npm run deploy
```

배포 후 표시되는 `https://...workers.dev` 주소를 루트 `local.properties`에
추가하고 앱을 다시 빌드합니다.

```properties
TRAFFIC_PROXY_BASE_URL=https://nomi-traffic-proxy.<계정>.workers.dev
```

앱은 이 값이 비어 있으면 기존 서울시 API를 직접 사용합니다. 값이 있으면
프록시만 사용하며, 프록시 장애 시 직접 API로 몰려 할당량을 소진하지 않고
기존 시간표 대체 안내를 사용합니다.

## 확인

```sh
curl "https://<주소>/health"
curl -i "https://<주소>/v1/subway/arrivals?station=정발산"
```

두 번째 요청부터 응답 헤더 `X-Nomi-Cache: HIT`가 표시되는지 확인합니다.
