package com.ecommerce.inventoryservice.controller;

import com.ecommerce.inventoryservice.model.InventoryItem;
import com.ecommerce.inventoryservice.service.InventoryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read + seed API for stock levels. The reserve/confirm/release transitions are
 * driven by Saga events over Kafka, not by HTTP, so they are intentionally not
 * exposed here.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public ResponseEntity<List<InventoryItem>> getAll() {
        return ResponseEntity.ok(inventoryService.getAll());
    }

    @GetMapping("/{productId}")
    public ResponseEntity<InventoryItem> getByProductId(@PathVariable Long productId) {
        return ResponseEntity.ok(inventoryService.getByProductId(productId));
    }

    @PostMapping
    public ResponseEntity<InventoryItem> upsert(@RequestBody StockRequest request) {
        return ResponseEntity.ok(
                inventoryService.upsertStock(request.productId(), request.availableQuantity()));
    }

    public record StockRequest(
            @NotNull Long productId,
            @Min(0) int availableQuantity) {
    }
}
