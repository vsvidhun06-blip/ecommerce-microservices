// Smoke test: a quick, low-load functional pass to confirm the system is wired
// before running the heavier scenarios. One VU, a handful of iterations.
//
//   k6 run load-tests/smoke.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, PRODUCT_ID, USER_ID, JSON_HEADERS } from './lib/config.js';

export const options = {
  vus: 1,
  iterations: 5,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1.0'],
  },
};

export default function () {
  // Read a product (cache-aside path).
  const read = http.get(`${BASE_URL}/api/v1/products/${PRODUCT_ID}`);
  check(read, {
    'product read 200': (r) => r.status === 200,
    'product has id': (r) => r.json('id') !== undefined,
  });

  // Create an order (saga + bulkhead path).
  const payload = JSON.stringify({
    userId: Number(USER_ID),
    items: [{ productId: Number(PRODUCT_ID), quantity: 1 }],
  });
  const order = http.post(`${BASE_URL}/api/v1/orders`, payload, JSON_HEADERS);
  check(order, {
    'order created 201': (r) => r.status === 201,
    'order has id': (r) => r.json('id') !== undefined,
  });

  sleep(1);
}
