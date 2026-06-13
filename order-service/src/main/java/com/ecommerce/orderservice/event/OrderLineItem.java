package com.ecommerce.orderservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One product line inside an {@link OrderCreatedEvent}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineItem {
    private Long productId;
    private int quantity;
}
