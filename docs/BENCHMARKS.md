# Benchmarks & Verification

A repeatable runbook for exercising the production-hardening work (Phases 2–8)
and capturing the numbers. Each section has **commands to run** and a **results
table to fill in**. Record the environment block once, then work top to bottom.

> Commands are written for a POSIX shell (Git Bash / WSL / macOS / Linux).
> PowerShell variants are given where the syntax differs.

## Environment (fill in)

| Field | Value |
| --- | --- |
| Date | |
| Host (CPU / cores / RAM) | |
| OS | |
| Docker version | |
| k6 version | |
| Git commit (`git rev-parse --short HEAD`) | |

## Service / port reference

| Component | URL | Notes |
| --- | --- | --- |
| API Gateway | http://localhost:8080 | entrypoint; rate limited |
| Product service | http://localhost:8081 | cache-aside reads |
| User service | http://localhost:8082 | |
| Order service | http://localhost:8083 | saga + bulkhead/CB |
| Notification service | http://localhost:8084 | |
| Inventory service | http://localhost:8085 | saga participant |
| Eureka | http://localhost:8761 | service registry |
| Zipkin | http://localhost:9411 | trace UI |
| Kafka (host listener) | localhost:9092 | container `ecommerce-kafka` |
| Redis | localhost:6379 | container `ecommerce-redis` |

---

## 0. Bring up the stack

```bash
docker compose up -d --build
# Wait until everything is healthy (Ctrl-C to stop watching):
watch docker compose ps
```

Expect all containers `Up` / `healthy`. Then sanity-check via the gateway:

```bash
curl -s http://localhost:8080/actuator/health        # gateway UP
curl -s http://localhost:8081/actuator/health         # product UP
curl -s http://localhost:8083/actuator/health         # order UP (shows CB state)
```

### Seed data

```bash
k6 run load-tests/seed.js
# Note the printed product id and export it for the rest of the run:
export PID=<printed id>     # PowerShell: $env:PID="<id>"
```

`seed.js` creates a product through the gateway and stocks its inventory
generously so reservations succeed under load.

---

## 1. Product read — latency percentiles + cache hit rate

Drives the Redis cache-aside path (Phase 5). After warm-up almost every read
should be a cache hit, so latency should stay low and flat as VUs climb.

### Capture latency

```bash
k6 run -e PRODUCT_ID=$PID load-tests/product-read.js
```

Read the end-of-run summary. The relevant rows are `http_req_duration`
(`med`=P50, `p(95)`, `p(99)`) and `http_req_failed`.

### Capture cache hit rate

Two options — record whichever you use:

**A. Redis keyspace stats (infra-level).** Sample before and after the run and
diff:

```bash
docker exec ecommerce-redis redis-cli INFO stats | grep -E 'keyspace_(hits|misses)'
# hit_rate = hits / (hits + misses)
```
> Redis also backs the gateway rate limiter, so during a pure `product-read.js`
> run the counts are dominated by product GETs but not 100% exclusive. Note it.

**B. Application logs (exact, per-cache).** Set
`logging.level.com.ecommerce.productservice=DEBUG` (env
`LOGGING_LEVEL_COM_ECOMMERCE_PRODUCTSERVICE=DEBUG` on the product-service
container), re-run, then count:

```bash
docker compose logs product-service | grep -c "Cache hit"
docker compose logs product-service | grep -c "Cache miss"
```

### Results (fill in)

| Metric | Value |
| --- | --- |
| Peak VUs | 200 (script default) |
| P50 latency (ms) | |
| P95 latency (ms) | |
| P99 latency (ms) | |
| Requests/s (avg) | |
| Error rate (`http_req_failed`) | |
| Cache hits / misses | |
| **Cache hit rate** | |
| Thresholds passed? (k6 exit 0) | |

---

## 2. Order create — throughput, error rate, circuit-breaker trips

Drives the choreographed saga + the thread-pool bulkhead and circuit breaker
around the product lookup (Phases 2–4). Uses an open arrival-rate model so the
system can't back-pressure the load generator — saturation shows up as latency,
429/5xx, and bulkhead rejections.

### Snapshot circuit-breaker state BEFORE

```bash
curl -s http://localhost:8083/actuator/circuitbreakers | jq '.circuitBreakers.productService.state'
# expect "CLOSED"
```

### Run the load test

```bash
k6 run -e PRODUCT_ID=$PID -e USER_ID=1 load-tests/order-create.js
```

From the summary record `http_reqs` (count + rate = throughput),
`http_req_failed` (error rate), and the custom `bulkhead_rejected` rate.

### Snapshot circuit-breaker AFTER (and during, if you can)

```bash
curl -s http://localhost:8083/actuator/circuitbreakers | jq '.circuitBreakers.productService'
curl -s http://localhost:8083/actuator/metrics/resilience4j.circuitbreaker.calls | jq
# Transitions (CLOSED->OPEN->HALF_OPEN) are logged by order-service:
docker compose logs order-service | grep -iE "circuit|CallNotPermitted" | tail
```

> To force trips, re-run while the product service is stressed or stopped
> (`docker compose stop product-service`); expect the breaker to go `OPEN`,
> orders to fail fast via the fallback, then recover after restart.

### Results (fill in)

| Metric | Value |
| --- | --- |
| Peak target rate (req/s) | 150 (script default) |
| Total requests | |
| **Throughput (req/s)** | |
| 201 success count | |
| Error rate (`http_req_failed`) | |
| Bulkhead rejection rate (`bulkhead_rejected`) | |
| Circuit-breaker state reached | CLOSED / OPEN / HALF_OPEN |
| CB trips observed (count) | |
| P95 latency (ms) | |
| Thresholds passed? | |

---

## 3. Zipkin — one full order → inventory → confirm saga trace

Confirms trace context propagates across HTTP **and** Kafka (Phase 7), so a
single order's create → reserve → confirm flow is one connected trace.

```bash
# Create a single order through the gateway
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"items\":[{\"productId\":$PID,\"quantity\":1}]}" | jq
```

Then in the Zipkin UI (http://localhost:9411):

1. **Run Query** filtered by `serviceName=order-service`.
2. Open the most recent trace and confirm the spans chain across services:

```
api-gateway  POST /api/v1/orders
  └─ order-service    createOrder  (HTTP server span)
       ├─ order-service    GET lb://product-service/... (HTTP client span)
       │    └─ product-service  GET /api/v1/products/{id}
       ├─ order-service    publish order-created      (Kafka producer span)
       │    └─ inventory-service  consume order-created (Kafka consumer span)
       │         └─ inventory-service  publish inventory-reserved
       │              └─ order-service  consume inventory-reserved → CONFIRMED
```

The whole chain shares one **traceId**. Capture it.

### Results (fill in)

| Field | Value |
| --- | --- |
| traceId | |
| # spans in trace | |
| Services touched | gateway, order, product, inventory |
| Total trace duration (ms) | |
| Kafka producer/consumer spans present? | yes / no |
| Screenshot / export saved to | |

---

## 4. Rate limiter — exceed 20 req/s and confirm 429s

The gateway applies a Redis token bucket per client IP: 10 req/s steady, burst
20 (Phase 8). A fast burst past the bucket returns HTTP 429.

```bash
# 60 rapid requests; tally status codes
for i in $(seq 1 60); do
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/v1/products/$PID"
done | sort | uniq -c
```

PowerShell:

```powershell
1..60 | ForEach-Object {
  try { (Invoke-WebRequest "http://localhost:8080/api/v1/products/$env:PID" -UseBasicParsing).StatusCode }
  catch { $_.Exception.Response.StatusCode.value__ }
} | Group-Object | Select-Object Count, Name
```

Expect a mix of `200` (up to the burst capacity) and `429` (rejected). Inspect
the rate-limit headers on a single call:

```bash
curl -s -D - -o /dev/null "http://localhost:8080/api/v1/products/$PID" | grep -i ratelimit
# X-RateLimit-Remaining / X-RateLimit-Burst-Capacity / X-RateLimit-Replenish-Rate
```

### Results (fill in)

| Metric | Value |
| --- | --- |
| Requests sent | 60 |
| 200 count | |
| 429 count | |
| First request # to get 429 | |
| `X-RateLimit-Burst-Capacity` header | 20 |
| Behaves as configured (10/s, burst 20)? | |

---

## 5. DLQ — inject a poison message, confirm it lands on `.DLT`

Confirms the dead-letter pipeline (Phase 4): a record that fails after the
bounded retries is republished to `<topic>.DLT` instead of blocking the
partition. Inventory consumes `order-created`, so a malformed JSON value there
is the cleanest injection point → it should route to `order-created.DLT`.

```bash
# 1. Start a consumer on the DLT in one terminal:
docker exec -it ecommerce-kafka \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order-created.DLT --from-beginning --property print.headers=true

# 2. In another terminal, produce a poison (unparseable) record to order-created:
echo 'this-is-not-json' | docker exec -i ecommerce-kafka \
  kafka-console-producer --bootstrap-server localhost:9092 --topic order-created
```

Within a few seconds (2 retries × 1s back-off) the bad record appears on the
DLT consumer, carrying `kafka_dlt-*` headers (original topic, exception class,
message). Cross-check the inventory log:

```bash
docker compose logs inventory-service | grep -iE "Deserialization|DLT|error handler" | tail
```

List topics to confirm the DLT exists:

```bash
docker exec ecommerce-kafka kafka-topics --bootstrap-server localhost:9092 --list | grep DLT
```

### Results (fill in)

| Field | Value |
| --- | --- |
| Topic injected | order-created |
| DLT topic | order-created.DLT |
| Poison record arrived on DLT? | yes / no |
| Time to DLT (approx) | ~2–3 s (2 retries @ 1s) |
| `kafka_dlt-exception-fqcn` header value | |
| Partition kept advancing (no wedge)? | yes / no |

---

## Teardown

```bash
docker compose down            # keep volumes
docker compose down -v         # also drop DB/Redis data
```

## Notes / observations

_Free-form: anomalies, retries needed, threshold failures, follow-ups._
