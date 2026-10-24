package com.dh.order.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 고객 메일이 나가지 못한 기록(order.api#44). 재시도 큐가 아니라 "누구에게 안 갔는지"를 남기는 용도다.
@Entity
@Table(name = "notification_failures")
@Getter
@NoArgsConstructor
public class NotificationFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** SHIPPED, REFUNDED 등 어떤 메일이었는지. */
    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public NotificationFailure(Long orderId, String kind, String lastError) {
        this.orderId = orderId;
        this.kind = kind;
        this.lastError = lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500);
    }
}
