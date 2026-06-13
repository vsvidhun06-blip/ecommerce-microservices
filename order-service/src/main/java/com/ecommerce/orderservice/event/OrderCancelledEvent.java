package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Saga compensation (published here). Emitted when an order is cancelled so the
 * inventory service releases any reservation it still holds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    private String eventId;
    private Long orderId;
    private String reason;
}
