package com.ecommerce.inventoryservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One product line inside an {@link OrderCreatedEvent}: which product and how
 * many units the order wants reserved.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineItem {
    private Long productId;
    private int quantity;
}
