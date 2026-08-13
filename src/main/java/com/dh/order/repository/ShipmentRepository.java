package com.dh.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.order.domain.Shipment;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByOrderId(Long orderId);
}
