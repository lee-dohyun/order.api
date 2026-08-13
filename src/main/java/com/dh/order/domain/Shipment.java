package com.dh.order.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 주문 1건당 배송 1건(부분 배송 미지원). 결제 완료(PAID) 주문에 admin이 운송장을 등록하면 생성된다.
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, length = 50)
    private String carrier;

    @Column(name = "tracking_number", nullable = false, length = 50)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status = ShipmentStatus.PREPARING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Shipment(Order order, String carrier, String trackingNumber) {
        this.order = order;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = ShipmentStatus.SHIPPED;
        this.shippedAt = LocalDateTime.now();
    }

    public void markDelivered() {
        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }
}
