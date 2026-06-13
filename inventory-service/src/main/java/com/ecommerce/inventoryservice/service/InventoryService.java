package com.ecommerce.inventoryservice.service;

import com.ecommerce.inventoryservice.model.InventoryItem;
import com.ecommerce.inventoryservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stock operations behind the inventory ledger.
 *
 * These are the building blocks the Saga choreography drives (the Kafka wiring
 * and idempotency live in the Saga phase): a reservation moves units from
 * available to reserved, a confirmation consumes the reservation, and a release
 * returns the units on compensation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public List<InventoryItem> getAll() {
        return inventoryRepository.findAll();
    }

    public InventoryItem getByProductId(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No inventory record for product " + productId));
    }

    /**
     * Create or overwrite the stock level for a product (admin/seed path).
     */
    @Transactional
    public InventoryItem upsertStock(Long productId, int availableQuantity) {
        InventoryItem item = inventoryRepository.findByProductId(productId)
                .orElseGet(() -> InventoryItem.builder()
                        .productId(productId)
                        .reservedQuantity(0)
                        .build());
        item.setAvailableQuantity(availableQuantity);
        InventoryItem saved = inventoryRepository.save(item);
        log.info("Stock set for product {}: available={}", productId, availableQuantity);
        return saved;
    }

    /**
     * Attempt to reserve {@code quantity} units of {@code productId}.
     *
     * @return true if the full quantity was reserved, false if there is no
     *         record or insufficient available stock. Optimistic locking
     *         (@Version on the entity) prevents two concurrent reservations
     *         from oversubscribing the same product.
     */
    @Transactional
    public boolean reserve(Long productId, int quantity) {
        InventoryItem item = inventoryRepository.findByProductId(productId).orElse(null);
        if (item == null || item.getAvailableQuantity() < quantity) {
            log.warn("Reservation rejected for product {} qty {} (available={})",
                    productId, quantity, item == null ? "none" : item.getAvailableQuantity());
            return false;
        }
        item.setAvailableQuantity(item.getAvailableQuantity() - quantity);
        item.setReservedQuantity(item.getReservedQuantity() + quantity);
        inventoryRepository.save(item);
        log.info("Reserved {} of product {} (available={}, reserved={})",
                quantity, productId, item.getAvailableQuantity(), item.getReservedQuantity());
        return true;
    }

    /**
     * Consume a previously held reservation (order confirmed -> stock ships).
     */
    @Transactional
    public void confirm(Long productId, int quantity) {
        InventoryItem item = inventoryRepository.findByProductId(productId).orElse(null);
        if (item == null) {
            return;
        }
        int toConsume = Math.min(quantity, item.getReservedQuantity());
        item.setReservedQuantity(item.getReservedQuantity() - toConsume);
        inventoryRepository.save(item);
        log.info("Confirmed {} of product {} (reserved now={})",
                toConsume, productId, item.getReservedQuantity());
    }

    /**
     * Return a held reservation to available stock (Saga compensation).
     */
    @Transactional
    public void release(Long productId, int quantity) {
        InventoryItem item = inventoryRepository.findByProductId(productId).orElse(null);
        if (item == null) {
            return;
        }
        int toRelease = Math.min(quantity, item.getReservedQuantity());
        item.setReservedQuantity(item.getReservedQuantity() - toRelease);
        item.setAvailableQuantity(item.getAvailableQuantity() + toRelease);
        inventoryRepository.save(item);
        log.info("Released {} of product {} (available={}, reserved={})",
                toRelease, productId, item.getAvailableQuantity(), item.getReservedQuantity());
    }
}
