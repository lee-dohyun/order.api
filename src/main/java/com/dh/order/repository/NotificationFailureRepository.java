package com.dh.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dh.order.domain.NotificationFailure;

public interface NotificationFailureRepository extends JpaRepository<NotificationFailure, Long> {
}
