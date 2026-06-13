// Shared configuration for the k6 load tests.
//
// Everything is overridable via environment variables so the same scripts run
// against the gateway, a single service, or a remote environment:
//   k6 run -e BASE_URL=http://localhost:8080 -e PRODUCT_ID=1 load-tests/product-read.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// A product that exists and has inventory stock (see seed.js / the README).
export const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
export const USER_ID = __ENV.USER_ID || '1';

// Inventory service is not exposed through the gateway; seeding hits it directly.
export const INVENTORY_URL = __ENV.INVENTORY_URL || 'http://localhost:8085';

export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
