package com.dh.order.claim;

import com.dh.order.domain.OrderItem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 클레임이 돌려받는 주문 품목과 수량(gateway#320). */
@Entity
@Table(name = "order_claim_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderClaimItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private OrderClaim claim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(nullable = false)
    private Integer quantity;

    public OrderClaimItem(OrderItem orderItem, int quantity) {
        this.orderItem = orderItem;
        this.quantity = quantity;
    }
}
