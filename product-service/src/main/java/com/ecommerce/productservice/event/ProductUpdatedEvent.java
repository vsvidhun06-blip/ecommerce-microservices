package com.ecommerce.productservice.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductUpdatedEvent {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stockQuantity;
    private LocalDateTime updatedAt;

    public ProductUpdatedEvent() {
    }

    public ProductUpdatedEvent(Long id, String name, BigDecimal price,
                               Integer stockQuantity, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "ProductUpdatedEvent{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", price=" + price +
                ", stockQuantity=" + stockQuantity +
                ", updatedAt=" + updatedAt +
                '}';
    }
}