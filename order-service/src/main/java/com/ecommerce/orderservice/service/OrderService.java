package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.config.KafkaTopics;
import com.ecommerce.orderservice.dto.CreateOrderRequest;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.event.OrderCancelledEvent;
import com.ecommerce.orderservice.event.OrderCreatedEvent;
import com.ecommerce.orderservice.event.OrderLineItem;
import com.ecommerce.orderservice.exception.OrderNotFoundException;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String PRODUCT_SERVICE_URL = "http://localhost:8081/api/v1/products";

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // Calculate total amount by fetching product prices
        BigDecimal totalAmount = BigDecimal.ZERO;

        Order order = Order.builder()
                .userId(request.getUserId())
                .status(Order.OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (OrderItemRequest itemRequest : request.getItems()) {
            // Fetch product details from Product Service
            try {
                ProductDTO product = restTemplate.getForObject(
                        PRODUCT_SERVICE_URL + "/" + itemRequest.getProductId(),
                        ProductDTO.class
                );

                if (product != null) {
                    BigDecimal itemTotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
                    totalAmount = totalAmount.add(itemTotal);

                    OrderItem orderItem = OrderItem.builder()
                            .order(order)
                            .productId(itemRequest.getProductId())
                            .quantity(itemRequest.getQuantity())
                            .price(product.getPrice())
                            .build();

                    order.getItems().add(orderItem);
                }
            } catch (Exception e) {
                log.error("Failed to fetch product details for product ID: {}", itemRequest.getProductId(), e);
                throw new RuntimeException("Product not found: " + itemRequest.getProductId());
            }
        }

        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);

        log.info("Order created successfully with ID: {} (PENDING)", savedOrder.getId());

        // Saga step 1: announce the PENDING order so inventory reserves stock.
        // The order stays PENDING until INVENTORY_RESERVED / INVENTORY_FAILED
        // drives it to CONFIRMED / CANCELLED (see OrderSagaHandler).
        publishOrderCreated(savedOrder);

        return OrderResponse.fromOrder(savedOrder);
    }

    /**
     * Saga callback: stock was reserved -> confirm the order.
     *
     * Idempotent: only a PENDING order transitions, so a duplicate
     * INVENTORY_RESERVED delivery is a no-op.
     */
    @Transactional
    public void markConfirmed(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("[saga] INVENTORY_RESERVED for unknown order {}; ignoring", orderId);
            return;
        }
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            log.info("[saga] order {} already {}; confirm is a no-op", orderId, order.getStatus());
            return;
        }
        order.setStatus(Order.OrderStatus.CONFIRMED);
        orderRepository.save(order);
        log.info("[saga] order {} CONFIRMED", orderId);
    }

    /**
     * Saga callback: stock could not be reserved -> cancel the order.
     *
     * Terminal failure of the forward flow; no compensation event is published
     * because the inventory service never held a reservation. Idempotent: only
     * a PENDING order transitions.
     */
    @Transactional
    public void failOrder(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("[saga] INVENTORY_FAILED for unknown order {}; ignoring", orderId);
            return;
        }
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            log.info("[saga] order {} already {}; cancel is a no-op", orderId, order.getStatus());
            return;
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.warn("[saga] order {} CANCELLED (inventory failed: {})", orderId, reason);
    }

    private void publishOrderCreated(Order order) {
        List<OrderLineItem> lines = order.getItems().stream()
                .map(i -> new OrderLineItem(i.getProductId(), i.getQuantity()))
                .collect(Collectors.toList());
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID().toString(), order.getId(), order.getUserId(), lines);
        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, order.getId().toString(), event);
        log.info("[saga] published ORDER_CREATED for order {} ({} lines)",
                order.getId(), lines.size());
    }

    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
        return OrderResponse.fromOrder(order);
    }

    public List<OrderResponse> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(OrderResponse::fromOrder)
                .collect(Collectors.toList());
    }

    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::fromOrder)
                .collect(Collectors.toList());
    }

    @Transactional
    public OrderResponse updateOrderStatus(Long id, Order.OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        Order.OrderStatus previous = order.getStatus();
        order.setStatus(status);
        Order updatedOrder = orderRepository.save(order);
        log.info("Order status updated from {} to {} for order ID: {}", previous, status, id);

        // Saga compensation: a user/admin cancelling an order that may hold a
        // reservation must tell inventory to release it. Inventory's handler is
        // idempotent and no-ops when no reservation is held, so it is safe to
        // emit on any transition into CANCELLED from a live state.
        if (status == Order.OrderStatus.CANCELLED && previous != Order.OrderStatus.CANCELLED) {
            publishOrderCancelled(order, "Cancelled via status update");
        }

        return OrderResponse.fromOrder(updatedOrder);
    }

    private void publishOrderCancelled(Order order, String reason) {
        OrderCancelledEvent event = new OrderCancelledEvent(
                UUID.randomUUID().toString(), order.getId(), reason);
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, order.getId().toString(), event);
        log.info("[saga] published ORDER_CANCELLED for order {} (compensation)", order.getId());
    }

    @Transactional
    public void deleteOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        orderRepository.delete(order);
        log.info("Order deleted with ID: {}", id);
    }

    // Inner DTO class for Product Service response
    private static class ProductDTO {
        private Long id;
        private String name;
        private BigDecimal price;
        private Integer stockQuantity;

        public ProductDTO() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public Integer getStockQuantity() { return stockQuantity; }
        public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    }
}