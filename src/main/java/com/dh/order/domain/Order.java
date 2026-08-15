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
