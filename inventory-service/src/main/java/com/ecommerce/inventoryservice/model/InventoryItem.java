package com.ecommerce.inventoryservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stock ledger for a single product.
 *
 * Stock is split into two buckets so a Saga reservation is reversible:
 *   availableQuantity  -- free to be reserved by new orders
 *   reservedQuantity   -- held for in-flight orders, not yet shipped
 *
 * Reserve:   available -= qty, reserved += qty   (ORDER_CREATED)
 * Confirm:   reserved  -= qty                     (order confirmed, stock ships)
 * Release:   reserved  -= qty, available += qty   (compensation / ORDER_CANCELLED)
 *
 * The {@code @Version} column gives optimistic locking so two concurrent
 * reservations on the same product cannot both succeed past the available count.
 */
@Entity
@Table(name = "inventory_items",
        uniqueConstraints = @UniqueConstraint(columnNames = "product_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Version
    @Column(name = "version")
    private Long version;
}
