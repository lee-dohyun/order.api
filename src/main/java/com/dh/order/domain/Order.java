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

    @jakarta.persistence.ManyToOne(fetch = FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    /**
     * 주문의 소유자. Keycloak sub(불변 UUID)이며 게이트웨이가 X-User-Id로 전달한다.
     * 이메일은 사용자가 바꿀 수 있어서 소유자 키로 쓸 수 없다 - 조회/인가는 항상 이 값 기준.
     * 비로그인 게스트 주문은 null이고 대신 {@link #guestToken}을 갖는다.
     */
    @Column(name = "customer_id", length = 36)
    private String customerId;

    /**
     * 표시/통지용 이메일 스냅샷. 소유자 판정에 쓰지 말 것 - 단, customer_id 백필 전의
     * 레거시 행(customer_id가 null인 회원 주문)은 아직 이 값으로만 조회할 수 있다.
     */
    @Column(length = 320)
    private String customerEmail;

    /**
     * 게스트 주문 접근 토큰. 소유자 계정이 없는 주문은 이 값을 아는 클라이언트(주문을 방금 만든
     * 브라우저)만 조회/결제할 수 있다. 생성 응답에서 한 번만 내려준다.
     */
    @Column(name = "guest_token", length = 36)
    private String guestToken;

    /**
     * 주문 생성 멱등 키(gateway#306). 클라이언트가 주문 시도마다 만들어 {@code Idempotency-Key} 헤더로
     * 보낸다. 같은 키의 재시도는 새 주문을 만들지 않고 이 주문을 돌려받는다 — 유니크 인덱스(V10)가 최종 방어.
     */
    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    /** 그 키로 처음 받은 요청 내용의 SHA-256(hex). 같은 키에 다른 내용이 오면 거부한다. */
    @Column(name = "idempotency_request_hash", length = 64, updatable = false)
    private String idempotencyRequestHash;

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

    /** 결제 금액. 등급 할인({@link #discountAmount})이 이미 빠진 값이다. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /**
     * 회원 등급 할인액과 그 근거 스냅샷(gateway#82). 등급은 매월 재산정되고 할인율은 정책 테이블이라
     * 바뀌므로, 주문 시점 값을 남겨 두지 않으면 이 주문이 왜 이 금액이었는지 복원할 수 없다.
     * 게스트 주문과 등급 조회에 실패한 주문은 할인 0, 등급 null 이다.
     */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "grade_code", length = 20)
    private String gradeCode;

    @Column(name = "grade_discount_rate", precision = 5, scale = 2)
    private BigDecimal gradeDiscountRate;

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

    /**
     * 이 주문을 조회/결제할 수 있는 요청인지 판정한다. 우선순위가 중요하다 —
     * <ol>
     *   <li>소유자 계정이 있으면(customerId) 그 계정만. 이메일은 보지 않는다.</li>
     *   <li>customerId가 아직 백필되지 않은 레거시 회원 주문은 이메일로 폴백한다.</li>
     *   <li>둘 다 없으면 게스트 주문이므로 guestToken이 일치해야 한다.</li>
     * </ol>
     * 셋 다 해당하지 않으면 거부다. 특히 게스트 주문에 토큰 없이 접근하는 것을
     * "소유자가 없으니 통과"로 처리하면 안 된다 — 그게 #214의 취약점이었다.
     */
    public boolean isAccessibleBy(String userId, String userEmail, String presentedGuestToken) {
        if (customerId != null) {
            return customerId.equals(userId);
        }
        if (customerEmail != null) {
            return customerEmail.equalsIgnoreCase(userEmail);
        }
        return guestToken != null && guestToken.equals(presentedGuestToken);
    }
}
