// Order creation load test — drives the choreographed saga (Phase 2) and the
// thread-pool bulkhead + circuit breaker around the product lookup (Phases 3-4).
//
// Uses a constant arrival rate (open model) so the offered load is independent
// of how fast the system responds: if order-service saturates, latency and the
// bulkhead-rejection rate climb rather than the test backing off. A modest rate
// of bulkhead rejections (HTTP 500 with a "bulkhead"/"lookup failed" body) under
// the highest stage is the expected, designed back-pressure — not a regression.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e PRODUCT_ID=1 -e USER_ID=1 load-tests/order-create.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, PRODUCT_ID, USER_ID, JSON_HEADERS } from './lib/config.js';

const bulkheadRejected = new Rate('bulkhead_rejected');

export const options = {
  scenarios: {
    create_orders: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 300,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 150 },  // push past the bulkhead pool+queue
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Allow some rejections at peak, but most orders must still be accepted.
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<1500'],
    checks: ['rate>0.90'],
  },
};

const payload = JSON.stringify({
  userId: Number(USER_ID),
  items: [{ productId: Number(PRODUCT_ID), quantity: 1 }],
});

export default function () {
  const res = http.post(`${BASE_URL}/api/v1/orders`, payload, JSON_HEADERS);

  // 201 = accepted (order persisted PENDING; saga drives it to CONFIRMED async).
  check(res, {
    'status is 201': (r) => r.status === 201,
    'order is PENDING or CONFIRMED': (r) => {
      if (r.status !== 201) return false;
      const s = r.json('status');
      return s === 'PENDING' || s === 'CONFIRMED';
    },
  });

  // Track designed back-pressure separately from genuine errors.
  bulkheadRejected.add(res.status >= 500 && String(res.body).includes('lookup failed'));

  sleep(1);
}
