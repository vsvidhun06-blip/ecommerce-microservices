package com.ecommerce.inventoryservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Saga step 2b (published here). Stock could not be reserved (no record or
 * insufficient quantity); the order service compensates by cancelling.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryFailedEvent {
    private String eventId;
    private Long orderId;
    private String reason;
}
