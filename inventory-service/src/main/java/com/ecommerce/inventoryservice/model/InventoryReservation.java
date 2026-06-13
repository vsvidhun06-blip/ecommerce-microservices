package com.ecommerce.inventoryservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-order record of the inventory service's Saga participation.
 *
 * Doubles as the idempotency guard: {@code orderId} is unique, so a duplicate
 * ORDER_CREATED delivery finds an existing row and re-publishes the prior
 * outcome instead of reserving stock a second time. It also remembers exactly
 * which lines were reserved, so an ORDER_CANCELLED compensation can release the
 * right quantities.
 */
@Entity
@Table(name = "inventory_reservations",
        uniqueConstraints = @UniqueConstraint(columnNames = "order_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservation {

    public enum Status { RESERVED, FAILED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "reason")
    private String reason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "inventory_reservation_lines",
            joinColumns = @JoinColumn(name = "reservation_id"))
    @Builder.Default
    private List<ReservedLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservedLine {
        @Column(name = "product_id")
        private Long productId;
        @Column(name = "quantity")
        private int quantity;
    }
}
