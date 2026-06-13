package com.ecommerce.inventoryservice.repository;

import com.ecommerce.inventoryservice.model.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryReservationRepository
        extends JpaRepository<InventoryReservation, Long> {

    Optional<InventoryReservation> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);
}
