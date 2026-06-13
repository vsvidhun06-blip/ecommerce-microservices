// One-shot seeding so the load tests have a product to read and stock to
// reserve. Run once before product-read.js / order-create.js:
//
//   k6 run load-tests/seed.js
//
// Creates a product via the gateway, then stocks its inventory by calling the
// inventory service directly (it is not exposed through the gateway). Prints the
// created product id — pass it to the other scripts with -e PRODUCT_ID=<id>.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, INVENTORY_URL, JSON_HEADERS } from './lib/config.js';

export const options = { vus: 1, iterations: 1 };

export default function () {
  const product = JSON.stringify({
    name: 'LoadTest Widget',
    description: 'Seeded by k6',
    price: 19.99,
    category: 'load-test',
    stockQuantity: 1000000,
    imageUrl: 'http://example.com/widget.png',
  });

  const created = http.post(`${BASE_URL}/api/v1/products`, product, JSON_HEADERS);
  check(created, { 'product created 201': (r) => r.status === 201 });
  const productId = created.json('id');
  console.log(`Seeded product id=${productId}`);

  // Stock inventory generously so reservations succeed under load.
  const stock = JSON.stringify({ productId: productId, availableQuantity: 1000000 });
  const stocked = http.post(`${INVENTORY_URL}/api/inventory`, stock, JSON_HEADERS);
  check(stocked, { 'inventory stocked 200': (r) => r.status === 200 });

  console.log(`Run the load tests with:  -e PRODUCT_ID=${productId}`);
}
