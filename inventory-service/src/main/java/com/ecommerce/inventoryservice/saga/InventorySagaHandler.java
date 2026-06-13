package com.ecommerce.inventoryservice.saga;

import com.ecommerce.inventoryservice.config.KafkaTopics;
import com.ecommerce.inventoryservice.event.*;
import com.ecommerce.inventoryservice.model.InventoryReservation;
import com.ecommerce.inventoryservice.repository.InventoryReservationRepository;
import com.ecommerce.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inventory's half of the choreographed Order/Inventory Saga.
 *
 * Consumes ORDER_CREATED, attempts an all-or-nothing reservation across the
 * order's lines, and publishes INVENTORY_RESERVED or INVENTORY_FAILED. Consumes
 * ORDER_CANCELLED (compensation) and releases any reservation still held.
 *
 * Idempotency: every order's outcome is recorded in {@link InventoryReservation}
 * keyed uniquely by orderId. A duplicate ORDER_CREATED re-publishes the recorded
 * outcome instead of reserving again; a duplicate ORDER_CANCELLED on an
 * already-released reservation is a no-op.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventorySagaHandler {

    private final InventoryService inventoryService;
    private final InventoryReservationRepository reservationRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(
            topics = KafkaTopics.ORDER_CREATED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "orderCreatedListenerFactory")
    @Transactional
    public void onOrderCreated(OrderCreatedEvent event) {
        Long orderId = event.getOrderId();
        log.info("[saga] ORDER_CREATED received for order {} ({} lines)",
                orderId, event.getItems() == null ? 0 : event.getItems().size());

        // Idempotency: replay the recorded outcome for a duplicate delivery.
        var existing = reservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            InventoryReservation r = existing.get();
            log.info("[saga] order {} already processed (status={}), re-publishing outcome",
                    orderId, r.getStatus());
            if (r.getStatus() == InventoryReservation.Status.RESERVED) {
                publishReserved(orderId);
            } else if (r.getStatus() == InventoryReservation.Status.FAILED) {
                publishFailed(orderId, r.getReason());
            }
            return;
        }

        List<InventoryReservation.ReservedLine> reserved = new ArrayList<>();
        String failureReason = null;
        for (OrderLineItem item : event.getItems()) {
            if (inventoryService.reserve(item.getProductId(), item.getQuantity())) {
                reserved.add(new InventoryReservation.ReservedLine(
                        item.getProductId(), item.getQuantity()));
            } else {
                failureReason = "Insufficient stock for product " + item.getProductId();
                break;
            }
        }

        if (failureReason == null) {
            reservationRepository.save(InventoryReservation.builder()
                    .orderId(orderId)
                    .status(InventoryReservation.Status.RESERVED)
                    .lines(reserved)
                    .build());
            log.info("[saga] reserved all lines for order {} -> INVENTORY_RESERVED", orderId);
            publishReserved(orderId);
        } else {
            // Compensate the partial reservation made before the failing line.
            for (InventoryReservation.ReservedLine line : reserved) {
                inventoryService.release(line.getProductId(), line.getQuantity());
            }
            reservationRepository.save(InventoryReservation.builder()
                    .orderId(orderId)
                    .status(InventoryReservation.Status.FAILED)
                    .reason(failureReason)
                    .lines(new ArrayList<>())
                    .build());
            log.warn("[saga] reservation failed for order {}: {} -> INVENTORY_FAILED",
                    orderId, failureReason);
            publishFailed(orderId, failureReason);
        }
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_CANCELLED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "orderCancelledListenerFactory")
    @Transactional
    public void onOrderCancelled(OrderCancelledEvent event) {
        Long orderId = event.getOrderId();
        var existing = reservationRepository.findByOrderId(orderId);
        if (existing.isEmpty()) {
            log.info("[saga] ORDER_CANCELLED for order {} with no reservation; nothing to release",
                    orderId);
            return;
        }
        InventoryReservation r = existing.get();
        if (r.getStatus() != InventoryReservation.Status.RESERVED) {
            log.info("[saga] ORDER_CANCELLED for order {} already {}; no-op",
                    orderId, r.getStatus());
            return;
        }
        for (InventoryReservation.ReservedLine line : r.getLines()) {
            inventoryService.release(line.getProductId(), line.getQuantity());
        }
        r.setStatus(InventoryReservation.Status.RELEASED);
        reservationRepository.save(r);
        log.info("[saga] released reservation for cancelled order {}", orderId);
    }

    private void publishReserved(Long orderId) {
        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, orderId.toString(),
                new InventoryReservedEvent(UUID.randomUUID().toString(), orderId));
    }

    private void publishFailed(Long orderId, String reason) {
        kafkaTemplate.send(KafkaTopics.INVENTORY_FAILED, orderId.toString(),
                new InventoryFailedEvent(UUID.randomUUID().toString(), orderId, reason));
    }
}
