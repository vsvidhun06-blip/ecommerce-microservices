package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Saga step 2b (consumed here). Reservation failed -> compensate (cancel). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryFailedEvent {
    private String eventId;
    private Long orderId;
    private String reason;
}
