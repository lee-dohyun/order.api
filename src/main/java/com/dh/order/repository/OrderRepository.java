package com.dh.order.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.dh.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * payOrder처럼 조회 직후 트랜잭션 밖에서 원격 HTTP 호출(재고 차감)을 거쳐야 하는 경로
     * 전용. {@code Order.items}는 LAZY({@code open-in-view: false})라, 이 조회를 감싼
     * 트랜잭션이 끝나 엔티티가 detach된 뒤 items에 접근하면 LazyInitializationException으로
     * 죽는다 - 그래서 여기서 JOIN FETCH로 미리 초기화해 둔다.
     */
    // distinct 필수 - @OneToMany를 JOIN FETCH하면 JDBC 결과는 item 개수만큼 행이 늘어난다.
    // Optional<Order> 반환 메서드는 내부적으로 getSingleResult()를 타는데, distinct 없이는
    // item이 2개 이상인 주문마다 NonUniqueResultException으로 죽는다(같은 Order 객체가
    // 중복돼도 JPA는 신경 안 쓴다 - 행 개수만 본다). distinct는 in-memory 중복 제거를
    // 트리거해서 Order 인스턴스를 하나로 접어준다.
    /**
     * 주문 행 잠금(gateway#320). 출고 전 취소 확정과 운송장 등록이 서로를 기다리게 한다 — 둘 다 "아직 출고 전인가"를
     * 보고 쓰므로, 잠금 없이 동시에 들어오면 취소된 주문에 운송장이 붙는다. 클레임 금액 확정도 같은 잠금 아래에서
     * 앞선 클레임 수를 다시 확인한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query("select distinct o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    /**
     * 멱등 키로 이미 만들어진 주문을 찾는다(gateway#306). createOrder 는 트랜잭션 밖(NOT_SUPPORTED)에서
     * 응답을 조립하므로 {@link #findByIdWithItems} 와 같은 이유로 items 를 미리 초기화한다.
     */
    @Query("select distinct o from Order o left join fetch o.items where o.idempotencyKey = :key")
    Optional<Order> findByIdempotencyKeyWithItems(@Param("key") String key);

    /**
     * 내 주문 목록. customer_id(Keycloak sub)가 정답이고, 아직 백필되지 않은 레거시 행
     * (customer_id가 null인 회원 주문)만 이메일로 폴백한다. 게스트 주문은 두 값이 모두
     * null이라 어느 쪽 조건도 타지 않는다 — null == null로 남의 주문이 딸려오면 안 되므로
     * 각 분기에서 파라미터와 컬럼이 모두 not null인지 명시적으로 확인한다.
     */
    @Query("""
            select o from Order o
            where ((:customerId is not null and o.customerId = :customerId)
                or (o.customerId is null and :customerEmail is not null and o.customerEmail = :customerEmail))
              and o.createdAt >= :from and o.createdAt < :to
            order by o.createdAt desc, o.id desc
            """)
    List<Order> findMineBetween(@Param("customerId") String customerId, @Param("customerEmail") String customerEmail,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    List<Order> findAllByOrderByCreatedAtDesc();

    /**
     * 회원별 구매확정 금액 합계. auth.api 의 등급 산정 배치가 클러스터 내부망으로 호출한다.
     *
     * <p><b>DELIVERED 만 센다.</b> CREATED/PAID/PREPARING/SHIPPED 는 아직 확정이 아니고
     * CANCELLED/REFUNDED 는 확정이 취소된 것이다. "결제했으니 등급이 오른다"로 만들면
     * 주문 후 환불을 반복해 등급을 올릴 수 있다.
     *
     * <p><b>취소·반품으로 돌려준 상품 금액도 뺀다</b>(gateway#320) — 일부만 반품한 주문도 DELIVERED 로 남는다.
     *
     * <p><b>배송비는 뺀다</b>(gateway#322) — 등급은 상품 구매 금액으로 매긴다. 배송비를 넣으면 유료배송
     * 상품을 산 회원이 같은 상품 금액으로도 더 빨리 승급한다.
     *
     * <p><b>게스트 주문(customerId is null)은 제외한다</b> — 귀속시킬 회원이 없다.
     * 레거시 이메일 폴백도 쓰지 않는다. 등급은 금전적 혜택이라 "아마 이 사람일 것"으로
     * 집계하면 안 된다.
     */
    @Query("""
            select o.customerId as customerId, sum(o.totalPrice - o.shippingFee - o.returnedItemAmount) as confirmedAmount
            from Order o
            where o.customerId is not null
              and o.status = com.dh.order.domain.OrderStatus.DELIVERED
              and o.createdAt >= :since
            group by o.customerId
            """)
    List<PurchaseSummaryRow> sumConfirmedPurchasesSince(@Param("since") LocalDateTime since);

    /**
     * 회원 한 명의 구매확정 금액 합계. 집계 규칙은 {@link #sumConfirmedPurchasesSince} 와 <b>같아야 한다</b>
     * — 마이페이지의 "다음 등급까지 남은 금액"이 배치가 실제로 쓰는 금액과 어긋나면 안 된다(gateway#81).
     * 구매확정이 없으면 0 이다.
     */
    @Query("""
            select coalesce(sum(o.totalPrice - o.shippingFee - o.returnedItemAmount), 0)
            from Order o
            where o.customerId = :customerId
              and o.status = com.dh.order.domain.OrderStatus.DELIVERED
              and o.createdAt >= :since
            """)
    BigDecimal sumConfirmedPurchasesOf(@Param("customerId") String customerId, @Param("since") LocalDateTime since);

    /** 프로젝션 — 엔티티를 통째로 끌고 오지 않는다(회원 수만큼 커진다). */
    interface PurchaseSummaryRow {
        String getCustomerId();

        BigDecimal getConfirmedAmount();
    }
}
