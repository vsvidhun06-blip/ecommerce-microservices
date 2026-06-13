package com.ecommerce.inventoryservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Saga step 1 (consumed here). Published by the order service when an order is
 * persisted in PENDING; the inventory service reserves stock in response.
 *
 * {@code eventId} is the idempotency key — duplicate deliveries of the same
 * eventId/orderId must not reserve twice.
 *
 * Cross-service note: the order service publishes its own identically-shaped
 * class. Kafka JSON type headers are disabled, and the listener container
 * factory pins the target type, so the differing package names do not matter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private String eventId;
    private Long orderId;
    private Long userId;
    private List<OrderLineItem> items;
}
