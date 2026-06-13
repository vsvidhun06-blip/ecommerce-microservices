# Architecture

An event-driven e-commerce backend built as Spring Boot 3.2 microservices. It
demonstrates a choreographed Saga, layered resilience (bulkhead / circuit
breaker / retry / DLQ), cache-aside with distributed locking, distributed
tracing, and gateway rate limiting.

## 1. System overview

```
                                  ┌──────────────┐
   client ───────────────────────▶  API Gateway  │  :8080  (rate limited)
                                  └──────┬───────┘
                                         │ routes /api/v1/**
         ┌───────────────┬───────────────┼───────────────┬────────────────┐
         ▼               ▼               ▼               ▼                ▼
   ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌──────────────┐  ┌───────────┐
   │  product  │   │   user    │   │   order   │   │ notification │  │ inventory │
   │   :8081   │   │   :8082   │   │   :8083   │   │    :8084     │  │   :8085   │
   └─────┬─────┘   └─────┬─────┘   └─────┬─────┘   └──────┬───────┘  └─────┬─────┘
         │               │               │                │                │
   product-db        user-db         order-db       notification-db   inventory-db
   (Postgres)       (Postgres)      (Postgres)        (Postgres)       (Postgres)
         │                               │                                 │
         └── Redis (cache) ──┘           └──────── Kafka (events) ─────────┘
                  ▲                                    │
       gateway rate-limit bucket            all services ──▶ Zipkin (traces)
                                            registered in ──▶ Eureka (discovery)
```

| Service | Port | Responsibility | Datastore |
| --- | --- | --- | --- |
| api-gateway | 8080 | Single entrypoint, routing, rate limiting | — (Redis for buckets) |
| eureka-server | 8761 | Service registry / discovery | — |
| product-service | 8081 | Product catalog; cache-aside reads | product-db + Redis |
| user-service | 8082 | Users + JWT auth | user-db |
| order-service | 8083 | Orders; Saga orchestration (order side) | order-db |
| notification-service | 8084 | Reacts to events, sends notifications | notification-db |
| inventory-service | 8085 | Stock ledger; Saga participant (inventory side) | inventory-db |

**Infrastructure:** PostgreSQL (one DB per service, ports 5432–5436), Redis
(cache + rate-limit buckets), Apache Kafka + Zookeeper (event backbone), Zipkin
(trace collector). All defined in `docker-compose.yml`.

## 2. Design principles

- **Database per service.** Each service owns its schema; no cross-service DB
  access. State is shared only via events or API calls.
- **Choreographed events over orchestration.** Cross-service workflows are
  driven by Kafka events, not a central coordinator — services stay loosely
  coupled and independently deployable.
- **Fail fast, degrade gracefully.** Synchronous dependencies are wrapped in
  bulkhead/circuit breaker/retry; cache and tracing failures never fail the
  request.
- **Idempotent consumers.** Every event handler tolerates duplicate delivery
  (at-least-once Kafka semantics).

## 3. Request flow & service discovery

External traffic enters only through the **API Gateway** (Spring Cloud Gateway,
reactive). It matches `Path=/api/v1/<area>/**` predicates and forwards to the
owning service. Route URIs are injected per environment
(`${PRODUCT_SERVICE_URL:…}` etc.), so the same config targets `localhost` for a
local run and in-network hostnames under Docker.

Service-to-service calls use **client-side load balancing**: order-service calls
`lb://product-service`, resolved by Spring Cloud LoadBalancer against the
**Eureka** registry (so it follows instances, not a pinned host:port).

> Discovery nuance: the gateway itself routes via the injected URIs (it is not a
> Eureka client), while order-service is a Eureka client and uses `lb://`. Both
> are registered for visibility; only order-service load-balances through the
> registry today.

## 4. The Order / Inventory Saga (choreography)

Placing an order spans two services with no shared transaction. Consistency is
reached through a choreographed Saga over Kafka, with compensation.

```
 Order service                    Kafka                 Inventory service
 ─────────────                    ─────                 ─────────────────
 createOrder
   persist PENDING
   publish ─────────▶  order-created ──────────────▶  reserve stock
                                                         (all-or-nothing)
                                                       record reservation
                            ◀────── inventory-reserved ──┘  (success)
   mark CONFIRMED ◀─────────┘
                            ◀────── inventory-failed ────┐  (no stock)
   mark CANCELLED ◀─────────┘                            │
                                                         │
 user cancels CONFIRMED order                            │
   publish ─────────▶ order-cancelled ─────────────▶ release reservation
                                                       (compensation)
```

**Topics:** `order-created`, `inventory-reserved`, `inventory-failed`,
`order-cancelled` (+ a `.DLT` per consumed topic).

**Order states:** `PENDING → CONFIRMED` (reserved) or `PENDING → CANCELLED`
(no stock); a confirmed order can later be `CANCELLED` (emits compensation).

### Correctness properties

- **Idempotency (inventory):** every order's outcome is recorded in an
  `InventoryReservation` row keyed uniquely by `orderId`. A duplicate
  `order-created` replays the recorded outcome instead of reserving twice; a
  duplicate `order-cancelled` on a released reservation is a no-op.
- **Idempotency (order):** state transitions only fire from `PENDING`, so a
  duplicate `inventory-reserved`/`inventory-failed` is a no-op.
- **Atomic reservation:** inventory reserves all lines or none; a partial
  reservation is rolled back before publishing `inventory-failed`.
- **Concurrency:** the stock ledger uses an `@Version` optimistic lock, so two
  concurrent reservations on the same product can't oversell.
- **Stock model:** `availableQuantity` / `reservedQuantity` buckets make a
  reservation reversible — reserve moves available→reserved, release moves it
  back, confirm consumes the reserved units.

## 5. Resilience patterns

### 5.1 Synchronous call protection (order → product)

The order flow's one synchronous dependency — the product price lookup — is
wrapped in three stacked Resilience4j layers (outermost first):

```
@Retry ─▶ @CircuitBreaker ─▶ @Bulkhead(THREADPOOL) ─▶ RestTemplate ─▶ product-service
```

- **Retry** — 3 attempts, exponential back-off, only on transient errors
  (connect/timeout, 5xx). 4xx is a client error and is not retried.
- **Circuit breaker** — COUNT window of 10 (min 5 calls), trips at ≥50% failure,
  open 10s, then 3 half-open probes. While open, calls short-circuit.
- **Thread-pool bulkhead** — the call runs on a bounded pool (core 10 / max 20 /
  queue 50), isolating it so a slow product service can't exhaust the
  order-service Tomcat request threads.
- **Single fallback** on the outermost layer: once retries are exhausted, the
  breaker is open (`CallNotPermittedException`), or the bulkhead is full
  (`BulkheadFullException`), a typed `ProductLookupException` surfaces instead of
  a raw rejection. This is the back-pressure the load tests intentionally
  provoke.

### 5.2 Asynchronous event protection (DLQ)

Each Saga consumer container has a `DefaultErrorHandler` that retries a failing
record twice (1s back-off) and then a `DeadLetterPublishingRecoverer` routes it
to `<topic>.DLT`. A poison payload or persistently failing handler is parked for
inspection/replay instead of blocking the partition.

Two publishing templates back the recoverer, chosen by value type: a
String-key/`byte[]`-value template for **poison records** (value failed
deserialization, key did not) and the JSON template for **business-failure**
event POJOs. Consumers wrap deserializers in `ErrorHandlingDeserializer` so a
bad payload is a handled error, never a wedged consumer.

## 6. Caching — cache-aside with distributed locking

Product reads (`getProductById`) are cache-aside over Redis:

1. **Hit** → return the cached `Product`.
2. **Miss** → acquire a Redis distributed lock (`SET NX PX` + Lua
   compare-and-delete on a per-holder token). The lock holder double-checks the
   cache, loads from the DB, and repopulates. Concurrent readers wait briefly
   for the freshly cached value — preventing a **cache stampede**.
3. A reader that loses the lock and still finds the cache cold falls back to a
   direct DB load, so a slow holder never blocks the request.

Writes keep the cache coherent: create/update/`updateStock` refresh the entry
(from the managed JPA entity), delete evicts it. Every Redis interaction
degrades gracefully — if Redis is unreachable the cache is bypassed rather than
failing the request. Products serialize as JSON with the JavaTime module so
`LocalDateTime` timestamps round-trip.

## 7. Observability — distributed tracing

All six Boot services use Micrometer Tracing + Brave, reporting to **Zipkin**.
A request is one connected trace across every hop:

- **HTTP** in/out is auto-instrumented (order-service's `@LoadBalanced`
  RestTemplate is built via `RestTemplateBuilder` so the observation customizer
  applies and the trace continues into product-service).
- **Kafka** producers and listeners have observation enabled, so an order's
  `create → reserve → confirm` chain is stitched into a single trace across
  service boundaries.

`traceId`/`spanId` are added to logs automatically. Sampling is 1.0 (dev);
lower it in production.

## 8. Rate limiting

The gateway applies a Redis-backed `RequestRateLimiter` as a default filter on
every route: a token bucket **per client IP** (`KeyResolver`), 10 req/s steady
with bursts to 20. Over-limit requests get HTTP 429 with `X-RateLimit-*`
headers. Behind a real LB/proxy the key should switch to `X-Forwarded-For` or an
authenticated principal so buckets aren't shared.

## 9. Technology stack

| Concern | Choice |
| --- | --- |
| Language / runtime | Java 17, Spring Boot 3.2.0 |
| Cloud / discovery | Spring Cloud 2023.0.0, Netflix Eureka |
| Gateway | Spring Cloud Gateway (reactive) |
| Messaging | Apache Kafka (Spring Kafka) |
| Persistence | PostgreSQL, Spring Data JPA (Hibernate) |
| Cache / locks / rate-limit | Redis (Lettuce; reactive in gateway) |
| Resilience | Resilience4j (bulkhead, circuit breaker, retry) |
| Tracing | Micrometer Tracing + Brave → Zipkin |
| Auth | JWT (user-service) |
| Load testing | k6 (`load-tests/`) |
| Orchestration | Docker Compose |

## 10. Data ownership

Each service owns exactly one database; there is no shared schema and no
cross-service SQL. Cross-service data needs are met by:

- **API call** for synchronous reads (order → product for pricing), protected by
  the resilience stack.
- **Events** for state propagation (the Saga; product create/update events;
  user events to notification).

## 11. Known trade-offs & future work

- **Dual-write on publish.** Services persist then publish in the same method;
  a crash between the two can drop an event. A transactional **outbox** (CDC or
  polling publisher) would make publication atomic with the DB write.
- **No saga timeout.** An order stuck in `PENDING` (inventory never responds)
  isn't reaped. A scheduled timeout → auto-cancel would close this.
- **Gateway not load-balanced via Eureka.** Routes use injected URIs; adding the
  eureka-client + `lb://` routing would let the gateway follow instances.
- **Sampling at 1.0** is dev-only; production should sample a fraction.
- **Single-node infra.** Kafka/Redis/Postgres run one node each (replication
  factor 1); production needs clustering and the DLT consumers need an alerting
  + replay tool.
- **Local `application.properties` drift.** A couple of services carry stale
  local DB/port defaults that docker-compose overrides; worth normalizing.

## 12. Repository map

```
api-gateway/         routing + rate limiting (Phase 8)
eureka-server/       service registry
product-service/     catalog + Redis cache-aside (Phase 5)
order-service/       order API + Saga (order side) + Resilience4j (Phases 2–4)
inventory-service/   stock ledger + Saga (inventory side) + DLQ (Phases 2,4)
user-service/        users + JWT
notification-service/ event-driven notifications
load-tests/          k6 scripts (Phase 6)
docs/                ARCHITECTURE.md, BENCHMARKS.md
docker-compose.yml   full local stack
```

See `docs/BENCHMARKS.md` for the runbook that exercises and measures all of the
above.
