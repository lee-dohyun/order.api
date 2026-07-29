package com.dh.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
