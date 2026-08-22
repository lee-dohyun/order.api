package com.dh.order.domain;

import java.math.BigDecimal;

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

// productId만 참조하고 name/price는 주문 시점 스냅샷으로 저장 (catalogdb와 별개 DB라 FK 없음, 이후 상품 가격이 바뀌어도 과거 주문 금액은 안 바뀜)
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    // product.api의 SKU(variant) 식별자 - 재고 차감은 이 값 기준
    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(nullable = false, length = 200)
    private String productName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * 주문 시점 판매자 식별자 <b>스냅샷</b>. 1 = 자사(1P).
     *
     * <p>참조가 아니라 스냅샷이라 FK 가 없다 — 판매자가 나가도 "누가 팔았는가"는 남아야 한다.
     * 나중에 들어올 {@code offerId}(order.api#14)는 성격이 반대다: 참조라 오퍼가 삭제되면 끊긴다.
     * <b>둘 다 필요하고 하나로 합치면 안 된다.</b>
     */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    /** 주문 시점 판매자 상호 스냅샷. 표시·CS 용도. */
    @Column(name = "seller_name", nullable = false, length = 100)
    private String sellerName;
}
