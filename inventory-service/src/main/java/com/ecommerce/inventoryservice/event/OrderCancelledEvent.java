package com.ecommerce.inventoryservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Saga compensation (consumed here). Published by the order service when an
 * order is cancelled; the inventory service releases any reservation it still
 * holds for that order back to available stock.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    private String eventId;
    private Long orderId;
    private String reason;
}
