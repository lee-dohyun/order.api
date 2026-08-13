package com.dh.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인한 사용자가 주문한 경우에만 채워짐(게이트웨이가 X-User-Email로 전달) - 비로그인 게스트 주문은 null
    @Column(length = 320)
    private String customerEmail;

    @Column(nullable = false, length = 100)
    private String ordererName;

    @Column(nullable = false, length = 30)
    private String ordererPhone;

    @Column(nullable = false, length = 300)
    private String shippingAddress;

    // 구조화된 배송지 스냅샷 (선택). shippingAddress는 기존 체크아웃 호환을 위해 계속 필수로 남겨두고,
    // 저장된 배송지(member_addresses)를 선택해 주문하는 흐름이 붙으면 이쪽이 채워진다.
    @Column(length = 50)
    private String recipientName;

    @Column(length = 30)
    private String recipientPhone;

    @Column(length = 10)
    private String zipCode;

    @Column(length = 200)
    private String address1;

    @Column(length = 200)
    private String address2;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
