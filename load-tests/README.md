# Load tests (k6)

[k6](https://k6.io) scripts that exercise the resilience and caching work from
the earlier phases under load.

| Script | What it drives |
| --- | --- |
| `smoke.js` | Quick 1-VU functional pass (read a product, create an order). Run this first. |
| `seed.js` | One-shot: create a product + stock its inventory, prints the `PRODUCT_ID`. |
| `product-read.js` | Ramps product reads — exercises the Redis cache-aside path (Phase 5). |
| `order-create.js` | Constant arrival-rate order creation — exercises the saga + thread-pool bulkhead + circuit breaker (Phases 2–4). |

## Prerequisites

1. The stack is up: `docker compose up -d` from the repo root.
2. k6 installed (`brew install k6`, `choco install k6`, or the Docker image below).

## Running

```bash
# 1. Confirm wiring
k6 run load-tests/smoke.js

# 2. Seed data, note the printed product id
k6 run load-tests/seed.js

# 3. Load tests (use the seeded id)
k6 run -e PRODUCT_ID=<id> load-tests/product-read.js
k6 run -e PRODUCT_ID=<id> -e USER_ID=1 load-tests/order-create.js
```

### Without a local k6 install (Docker)

```bash
# --network host so the container can reach localhost:8080 etc.
docker run --rm --network host -v "$PWD/load-tests:/scripts" \
  grafana/k6 run -e PRODUCT_ID=1 /scripts/product-read.js
```

## Configuration (env vars)

| Var | Default | Notes |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | API gateway. Point at a service directly to bypass the gateway. |
| `PRODUCT_ID` | `1` | Must exist and have inventory stock (use `seed.js`). |
| `USER_ID` | `1` | Order owner. |
| `INVENTORY_URL` | `http://localhost:8085` | Inventory service (not gateway-routed); used by `seed.js`. |

## Reading the results

- **`product-read.js`** — after the warm-up stage almost every read is a Redis
  cache hit, so `http_req_duration` p95 should stay low and roughly flat as VUs
  climb. A rising p95 suggests cache misses (check Redis connectivity / TTL).
- **`order-create.js`** — uses an open (arrival-rate) model, so offered load does
  not back off when the system slows. At the peak stage some requests may be
  rejected by the product-lookup **bulkhead** — that is the designed
  back-pressure, tracked separately as the `bulkhead_rejected` metric, and is
  why `http_req_failed` is allowed up to 10%. Watch the circuit-breaker and
  bulkhead state at `GET http://localhost:8083/actuator/circuitbreakers` and
  `/actuator/bulkheads` while the test runs.

Thresholds are encoded in each script's `options.thresholds`; a failed threshold
makes `k6` exit non-zero, so these scripts are CI-friendly.
