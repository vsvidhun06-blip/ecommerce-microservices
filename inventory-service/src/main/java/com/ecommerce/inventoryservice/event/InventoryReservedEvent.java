package com.ecommerce.inventoryservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Saga step 2a (published here). Stock was successfully reserved for the order;
 * the order service confirms the order in response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservedEvent {
    private String eventId;
    private Long orderId;
}
