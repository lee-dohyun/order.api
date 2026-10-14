package com.dh.order.claim;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.dh.order.domain.Order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 고객 상품별 취소·반품 한 건(gateway#320, V12). 금액 칼럼은 완료 시점의 스냅샷이다 — 계산 규칙은
 * {@link ClaimRefundCalculator}, 다음 클레임의 안분·배송비 재계산이 이 값들을 읽는다.
 */
@Entity
@Table(name = "order_claims")
@Getter
@Setter
@NoArgsConstructor
public class OrderClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 30)
    private ClaimReason reasonType;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "item_amount", precision = 12, scale = 2)
    private BigDecimal itemAmount;

    @Column(name = "discount_share", precision = 12, scale = 2)
    private BigDecimal discountShare;

    @Column(name = "shipping_refund", precision = 12, scale = 2)
    private BigDecimal shippingRefund;

    @Column(name = "return_shipping_fee", precision = 12, scale = 2)
    private BigDecimal returnShippingFee;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "exchange_carrier", length = 50)
    private String exchangeCarrier;

    @Column(name = "exchange_tracking_number", length = 50)
    private String exchangeTrackingNumber;

    @Column(name = "exchange_shipped_at")
    private LocalDateTime exchangeShippedAt;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderClaimItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addItem(OrderClaimItem item) {
        item.setClaim(this);
        items.add(item);
    }

    /** 상품이 고객에게서 돌아오고 돈을 돌려주는 클레임(취소·반품). 교환은 상품이 바뀔 뿐 남은 상품 금액을 줄이지 않는다. */
    public boolean isRefundType() {
        return type == ClaimType.CANCEL || type == ClaimType.RETURN;
    }

    /** 고객 사유 — 배송비 재계산 대상. 취소는 언제나 고객 사유다. */
    public boolean isCustomerCaused() {
        return type == ClaimType.CANCEL || reasonType == ClaimReason.CHANGE_OF_MIND;
    }
}
