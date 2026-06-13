package com.ecommerce.productservice.service;

import com.ecommerce.productservice.event.ProductCreatedEvent;
import com.ecommerce.productservice.event.ProductUpdatedEvent;
import com.ecommerce.productservice.model.Product;
import com.ecommerce.productservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private final ProductRepository productRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ProductCacheService productCache;

    public ProductService(ProductRepository productRepository,
                          KafkaTemplate<String, Object> kafkaTemplate,
                          ProductCacheService productCache) {
        this.productRepository = productRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.productCache = productCache;
    }

    public Product createProduct(Product product) {
        Product savedProduct = productRepository.save(product);
        log.info("✅ Product created: ID={}, Name={}", savedProduct.getId(), savedProduct.getName());
        productCache.put(savedProduct);

        // Publish event
        ProductCreatedEvent event = new ProductCreatedEvent(
                savedProduct.getId(),
                savedProduct.getName(),
                savedProduct.getPrice(),
                savedProduct.getCategory(),
                savedProduct.getStockQuantity(),
                savedProduct.getCreatedAt()
        );

        kafkaTemplate.send("product-created", savedProduct.getId().toString(), event);
        log.info("📤 Published product-created event for product ID: {}", savedProduct.getId());

        return savedProduct;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public List<Product> getActiveProducts() {
        return productRepository.findByActiveTrue();
    }

    public Product getProductById(Long id) {
        // Cache-aside: serve from Redis on a hit; on a miss, a single holder
        // (distributed lock) loads from the DB and repopulates the cache.
        return productCache.getOrLoad(id, () -> productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + id)));
    }

    public List<Product> getProductsByCategory(String category) {
        return productRepository.findByCategory(category);
    }

    public List<Product> searchProducts(String name) {
        return productRepository.findByNameContainingIgnoreCase(name);
    }

    public Product updateProduct(Long id, Product updatedProduct) {
        // Mutations operate on the managed JPA entity (not a cached copy).
        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + id));

        existingProduct.setName(updatedProduct.getName());
        existingProduct.setDescription(updatedProduct.getDescription());
        existingProduct.setPrice(updatedProduct.getPrice());
        existingProduct.setCategory(updatedProduct.getCategory());
        existingProduct.setStockQuantity(updatedProduct.getStockQuantity());
        existingProduct.setActive(updatedProduct.getActive());
        existingProduct.setImageUrl(updatedProduct.getImageUrl());

        Product saved = productRepository.save(existingProduct);
        productCache.put(saved);
        log.info("✅ Product updated: ID={}", id);

        // Publish event
        ProductUpdatedEvent event = new ProductUpdatedEvent(
                saved.getId(),
                saved.getName(),
                saved.getPrice(),
                saved.getStockQuantity(),
                saved.getUpdatedAt()
        );

        kafkaTemplate.send("product-updated", saved.getId().toString(), event);
        log.info("📤 Published product-updated event for product ID: {}", saved.getId());

        return saved;
    }

    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new RuntimeException("Product not found with id: " + id);
        }
        productRepository.deleteById(id);
        productCache.evict(id);
        log.info("🗑️ Product deleted: ID={}", id);
    }

    public void updateStock(Long productId, Integer quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + productId));
        product.setStockQuantity(quantity);
        Product saved = productRepository.save(product);
        productCache.put(saved);
        log.info("📦 Stock updated for product ID {}: new quantity = {}", productId, quantity);
    }
}