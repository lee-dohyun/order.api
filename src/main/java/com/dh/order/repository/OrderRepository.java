package com.dh.order.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    List<Order> findAllByOrderByCreatedAtDesc();
}
