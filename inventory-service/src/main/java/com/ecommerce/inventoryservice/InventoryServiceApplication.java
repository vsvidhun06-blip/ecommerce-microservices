package com.ecommerce.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory service.
 *
 * Owns product stock levels (available vs. reserved) and is the second
 * participant in the choreography-based Order/Inventory Saga: it consumes
 * ORDER_CREATED, attempts a stock reservation, and emits INVENTORY_RESERVED or
 * INVENTORY_FAILED back to the order service (wired in the Saga phase).
 */
@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
