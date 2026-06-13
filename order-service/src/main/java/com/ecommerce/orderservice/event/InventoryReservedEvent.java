package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Saga step 2a (consumed here). Stock reserved -> confirm the order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservedEvent {
    private String eventId;
    private Long orderId;
}
