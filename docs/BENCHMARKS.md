# Benchmarks & Verification

A repeatable runbook for exercising the production-hardening work (Phases 2–8)
and capturing the numbers. Each section has **commands to run** and a **results
table** — now filled in with a real run (see *Run log* below).

> Commands are written for a POSIX shell (Git Bash / WSL / macOS / Linux).
> PowerShell variants are given where the syntax differs.

## Run log

Captured 2026-06-13 on the environment below. All 15 containers came up healthy;
both load tests passed their k6 thresholds (exit 0). Two startup/runtime bugs on
the saga path were found and fixed to get there — see
[§ Issues found & fixed](#issues-found--fixed-during-this-run). A note on why the
heavy load tests target services directly rather than the gateway is in
[§ Methodology](#methodology-gateway-vs-direct).

## Environment

| Field | Value |
| --- | --- |
| Date | 2026-06-13 |
| Host (CPU / cores / RAM) | AMD Ryzen 9 7940HX · 16C / 32T · 15.2 GB RAM |
| OS | Windows 11 Home 10.0.26200 (Git Bash + Docker Desktop) |
| Docker version | 28.5.1 (Compose v2.40.3-desktop.1) |
| k6 version | v2.0.0 (windows/amd64) |
| Git commit (base) | `3a075c6` on `feat/production-upgrades` (+ the two fixes below) |

## Methodology: gateway vs direct

The Phase 8 gateway applies a Redis `RequestRateLimiter` as a **default filter on
every route** — 10 req/s steady, burst 20, **per client IP**
(`api-gateway/.../application.properties`). A k6 load test runs from a single
host IP, so anything driven *through* the gateway is capped at ~10 req/s and the
rest comes back `429` before it ever reaches the cache / saga / bulkhead layers.
A first product-read run through `:8080` confirmed this — it was almost entirely
`429`, with a near-zero cache hit rate.

So to measure each layer's real behaviour, the heavy tests target the **service
directly** (`BASE_URL=http://localhost:8081` / `:8083`), which the load-test
README explicitly supports ("Point at a service directly to bypass the gateway"),
and the rate limiter is exercised **separately** against the gateway in §4 —
which is the one place `429`s are the thing being measured.

## Service / port reference

All up and healthy for this run (`docker compose ps`).

| Component | URL | Notes | Status |
| --- | --- | --- | --- |
| API Gateway | http://localhost:8080 | entrypoint; rate limited | UP |
| Product service | http://localhost:8081 | cache-aside reads | UP |
| User service | http://localhost:8082 | | UP |
| Order service | http://localhost:8083 | saga + bulkhead/CB | UP |
| Notification service | http://localhost:8084 | | UP |
| Inventory service | http://localhost:8085 | saga participant | UP |
| Eureka | http://localhost:8761 | service registry | healthy |
| Zipkin | http://localhost:9411 | trace UI | healthy |
| Kafka (host listener) | localhost:9092 | container `ecommerce-kafka` | healthy |
| Redis | localhost:6379 | container `ecommerce-redis` | healthy |

---

## Issues found & fixed during this run

The saga services (`order-service`, `inventory-service`) would not start, and
once started, order creation threw. Both are fixed in this change; numbers below
are from the fixed build.

1. **`KafkaTemplate<String, Object>` bean missing → `APPLICATION FAILED TO
   START`** (order-service *and* inventory-service). `KafkaDlqConfig` declares a
   `KafkaTemplate<String, byte[]> rawValueKafkaTemplate` for the DLT path. Spring
   Boot's auto-configured default `KafkaTemplate<String, Object>` is
   `@ConditionalOnMissingBean(KafkaTemplate.class)` — a condition that matches the
   **raw** type, ignoring generics — so the moment any `KafkaTemplate` bean
   exists it backs off and is never created. `OrderService`,
   `OrderSagaHandler`, `InventorySagaHandler`, and the DLQ recoverer all autowire
   `KafkaTemplate<String, Object>`, so the context failed.
   **Fix:** declare the JSON template explicitly over the still-auto-configured
   `ProducerFactory` in each `KafkaDlqConfig`.

2. **`Order.getItems()` returns `null` → `500` on every order create.** `Order`
   is a Lombok `@Builder`, and `Order.builder()…build()` **ignores field
   initializers** unless the field is `@Builder.Default`, so `items` was `null`
   and `order.getItems().add(orderItem)` NPE'd.
   **Fix:** `@Builder.Default` on `Order.items`. (`InventoryReservation` already
   had it; this was the only missing one.)

After the fixes, an end-to-end order create returns `201` and the saga drives it
to `CONFIRMED` (`order 1 -> INVENTORY_RESERVED -> CONFIRMED`, confirmed in the
order-service log).

---

## 0. Bring up the stack

```bash
docker compose up -d --build
watch docker compose ps   # until everything is Up / healthy
```

> Build note: building the six Spring services in parallel intermittently hit
> `SSL peer shut down incorrectly` / `Remote host terminated the handshake`
> against Maven Central (six concurrent `mvn` dependency pulls saturating TLS).
> Serializing the build (`COMPOSE_PARALLEL_LIMIT=1 docker compose build`) and/or
> simply retrying clears it; the host reaches Maven Central fine.

### Seed data

```bash
k6 run load-tests/seed.js          # prints the product id (here: 1)
export PID=1                        # PowerShell: $env:PID="1"
```

`seed.js` creates a product through the gateway and stocks its inventory. (The
inventory-stock step hits inventory-service directly, so it needs §0 to have
brought that service up healthy.)

---

## 1. Product read — latency percentiles + cache hit rate

Drives the Redis cache-aside path (Phase 5), **direct to product-service** to
bypass the gateway limiter (see [Methodology](#methodology-gateway-vs-direct)).
Ramps 0→50→200 VUs over 3m20s.

```bash
k6 run -e BASE_URL=http://localhost:8081 -e PRODUCT_ID=$PID load-tests/product-read.js
```

Cache hit rate via Redis keyspace stats, sampled before/after the run:

```bash
docker exec ecommerce-redis redis-cli INFO stats | grep -E 'keyspace_(hits|misses)'
# hit_rate = Δhits / (Δhits + Δmisses)
```

### Results

| Metric | Value |
| --- | --- |
| Target / port | product-service direct, `:8081` |
| Peak VUs | 200 |
| P50 latency (`med`) | **1.06 ms** |
| P95 latency | **2.02 ms** (threshold `<300` ✓) |
| P99 latency | **2.39 ms** (threshold `<800` ✓) |
| avg / p90 / max | 1.22 ms / 1.71 ms / 19.07 ms |
| Total requests | 42,921 |
| Requests/s (avg) | **214.2 /s** |
| Error rate (`http_req_failed`) | **0.00 %** (0 / 42,921) ✓ |
| `product_not_found` | 0.00 % ✓ |
| Checks passed | 100 % (85,842 / 85,842) |
| Redis Δ hits / misses | **+42,923 / +0** |
| **Cache hit rate** | **≈ 100 %** (single hot key, fully warmed) |
| Thresholds passed? (k6 exit 0) | **Yes** |

p95 stayed flat (~2 ms) all the way to 200 VUs — the cache-aside path is serving
reads from Redis, not the database.

---

## 2. Order create — throughput, error rate, circuit breaker, bulkhead

Drives the choreographed saga + the thread-pool bulkhead and circuit breaker
around the product lookup (Phases 2–4), **direct to order-service** (same
gateway-limiter reason). Open arrival-rate model ramping to 150 req/s.

```bash
curl -s http://localhost:8083/actuator/circuitbreakers | jq '.circuitBreakers.productService.state'   # CLOSED
k6 run -e BASE_URL=http://localhost:8083 -e PRODUCT_ID=$PID -e USER_ID=1 load-tests/order-create.js
```

### Results — steady state (product-service healthy)

| Metric | Value |
| --- | --- |
| Target / port | order-service direct, `:8083` |
| Peak target rate | 150 req/s (script) |
| Total requests | 7,626 |
| **Throughput** | **50.7 req/s** avg over the ramp (peak-stage target 150/s) |
| 201 success count | 7,626 (100 %) |
| Error rate (`http_req_failed`) | **0.00 %** (0 / 7,626) ✓ (threshold `<10%`) |
| Bulkhead rejection rate (`bulkhead_rejected`) | **0.00 %** (0 / 7,626) |
| Circuit-breaker state | **CLOSED** throughout (failureRate 0 %, 0 failed) |
| CB trips observed | **0** |
| P95 latency | **9.03 ms** (avg 6.7 / med 5.27 / p90 8.03 / max 344.8 ms) ✓ (`<1500`) |
| Checks passed | 100 % (15,252 / 15,252) |
| `dropped_iterations` | 98 (k6 VU-pool limit at peak arrival rate, not server errors) |
| Thresholds passed? | **Yes** |

Under load with a warm product cache the lookups never failed, so the bulkhead
never had to reject and the breaker correctly stayed CLOSED — the resilience
layer adds no overhead on the happy path (saga create p95 ~9 ms).

### Results — forced circuit-breaker trip

The breaker only trips on real downstream failure, so this was forced per the
runbook: stop product-service, then drive order creates so the lookups fail.

```bash
docker compose stop product-service
# fire ~25 order creates at :8083 …
curl -s http://localhost:8083/actuator/circuitbreakers | jq '.circuitBreakers.productService'
docker compose start product-service
```

| Metric | Value |
| --- | --- |
| Order creates while product-service down | 25 → 23× `500`, 2× `000` (connect fail) |
| **Circuit-breaker state reached** | **OPEN** |
| Failure rate at trip | 50.0 % (window 10, min 5 — config threshold met) |
| `failedCalls` / `bufferedCalls` | 5 / 10 |
| **`notPermittedCalls` (fast-failed via fallback)** | **25** |
| Recovery | breaker did **not** auto-close after restart — see note |

> **Recovery caveat (observed, not fixed).** After `docker compose start
> product-service`, the breaker stayed OPEN for minutes: `docker compose start`
> gives the container a new IP, and order-service's Spring Cloud LoadBalancer
> held the pre-restart instance, so every HALF_OPEN probe (`lb://product-service`)
> kept failing and the breaker re-opened — even though product-service was
> healthy and re-registered in Eureka (and reachable by hostname from the
> order-service container). Bouncing order-service to refresh its LB cache
> restored service immediately: order create → `201`, CB `CLOSED`. Worth a
> follow-up (LB cache TTL / eager refresh) if fast auto-recovery after an
> instance replacement matters.

---

## 3. Zipkin — one full order → inventory → confirm saga trace

Not re-captured in this run; the saga itself is verified — a single create at
`:8083` produced `order 1 -> INVENTORY_RESERVED -> CONFIRMED` in the
order-service log, and tracing is wired (`management.tracing.sampling.probability=1.0`,
Zipkin healthy at :9411). Follow the original steps in the UI to grab a traceId.

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"items\":[{\"productId\":$PID,\"quantity\":1}]}" | jq
# then in Zipkin (http://localhost:9411): Run Query, serviceName=order-service
```

| Field | Value |
| --- | --- |
| Saga completes (create → reserve → confirm)? | **yes** (order 1 → CONFIRMED, from log) |
| traceId | _(capture from Zipkin UI)_ |
| Kafka producer/consumer spans present? | wired (observation-enabled on template + listener) |

---

## 4. Rate limiter — exceed the bucket and confirm 429s

The gateway applies a Redis token bucket per client IP: 10 req/s steady, burst 20
(Phase 8). A fast burst past the bucket returns HTTP 429.

```bash
for i in $(seq 1 60); do
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/v1/products/$PID"
done | sort | uniq -c
curl -s -D - -o /dev/null "http://localhost:8080/api/v1/products/$PID" | grep -i ratelimit
```

### Results

| Metric | Value |
| --- | --- |
| Requests sent (rapid burst) | 60 |
| 200 count | **39** |
| 429 count | **21** |
| `X-RateLimit-Burst-Capacity` header | **20** |
| `X-RateLimit-Replenish-Rate` header | **10** |
| `X-RateLimit-Requested-Tokens` header | 1 |
| Behaves as configured (10/s, burst 20)? | **Yes** — ~20 burst + ~10/s replenish over the burst window served, the rest 429'd |

(This is also exactly why §1/§2 target services directly: the same limiter would
otherwise cap those load tests at ~10 req/s.)

---

## 5. DLQ — inject a poison message, confirm it lands on `.DLT`

Not re-captured in this run. The DLT pipeline is wired (`KafkaDlqConfig`
`DefaultErrorHandler` → `DeadLetterPublishingRecoverer`, 2 retries @ 1s); the
fix above is what makes those beans load at all. Original steps:

```bash
docker exec -it ecommerce-kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order-created.DLT --from-beginning --property print.headers=true
echo 'this-is-not-json' | docker exec -i ecommerce-kafka \
  kafka-console-producer --bootstrap-server localhost:9092 --topic order-created
docker exec ecommerce-kafka kafka-topics --bootstrap-server localhost:9092 --list | grep DLT
```

---

## Teardown

```bash
docker compose down            # keep volumes
docker compose down -v         # also drop DB/Redis data
```

## Notes / observations

- **Two saga-path bugs fixed** to make this run possible (KafkaTemplate bean +
  Lombok `@Builder.Default`); see the section above. order/inventory now boot
  clean and the saga completes to CONFIRMED.
- **Heavy load tests run direct to services**, not via the gateway, because the
  per-IP 10 req/s limiter would otherwise dominate the result with 429s. The
  limiter is validated on its own in §4.
- **CB auto-recovery after an instance replacement** is gated by the
  LoadBalancer cache, not the breaker — see the §2 recovery caveat.
- **Maven Central TLS flakiness** during parallel image builds — serialize or
  retry.
