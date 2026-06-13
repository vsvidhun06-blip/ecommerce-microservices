package com.ecommerce.orderservice.saga;

import com.ecommerce.orderservice.config.KafkaTopics;
import com.ecommerce.orderservice.event.InventoryFailedEvent;
import com.ecommerce.orderservice.event.InventoryReservedEvent;
import com.ecommerce.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The order service's half of the choreographed Order/Inventory Saga.
 *
 * After {@link OrderService#createOrder} persists an order in PENDING and
 * publishes ORDER_CREATED, this handler waits for the inventory service's
 * verdict and drives the order to its terminal state:
 *
 *   INVENTORY_RESERVED -> confirm the order  (PENDING -> CONFIRMED)
 *   INVENTORY_FAILED   -> cancel the order   (PENDING -> CANCELLED)
 *
 * Idempotency lives in {@link OrderService}: the state transitions only fire
 * from PENDING, so a duplicate delivery of either outcome is a no-op.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaHandler {

    private final OrderService orderService;

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RESERVED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "inventoryReservedListenerFactory")
    public void onInventoryReserved(InventoryReservedEvent event) {
        log.info("[saga] INVENTORY_RESERVED received for order {} -> confirming", event.getOrderId());
        orderService.markConfirmed(event.getOrderId());
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_FAILED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "inventoryFailedListenerFactory")
    public void onInventoryFailed(InventoryFailedEvent event) {
        log.warn("[saga] INVENTORY_FAILED received for order {}: {} -> cancelling",
                event.getOrderId(), event.getReason());
        orderService.failOrder(event.getOrderId(), event.getReason());
    }
}
