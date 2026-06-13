// Product read load test — exercises the Redis cache-aside path (Phase 5) and
// the order/product resilience layers indirectly. After warm-up nearly every
// request should be a cache hit, so p95 latency should stay low and flat even
// as the VU count climbs.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e PRODUCT_ID=1 load-tests/product-read.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, PRODUCT_ID } from './lib/config.js';

const notFound = new Rate('product_not_found');

export const options = {
  scenarios: {
    cache_read: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },   // warm the cache
        { duration: '1m', target: 50 },
        { duration: '30s', target: 200 },   // ramp hard
        { duration: '1m', target: 200 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Served from cache -> should stay fast under load.
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    product_not_found: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/products/${PRODUCT_ID}`);
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
    'has product id': (r) => r.json('id') !== undefined,
  });
  notFound.add(res.status === 404);
  if (!ok) {
    // Surface the first failing body in the k6 log for triage.
    console.error(`unexpected response ${res.status}: ${res.body}`);
  }
  sleep(0.5);
}
