package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Saga step 1 (published here). Emitted after the order is persisted in PENDING;
 * the inventory service reserves stock in response. {@code eventId} is the
 * idempotency key.
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
