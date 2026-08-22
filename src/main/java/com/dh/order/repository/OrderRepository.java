package com.dh.order.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dh.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 내 주문 목록. customer_id(Keycloak sub)가 정답이고, 아직 백필되지 않은 레거시 행
     * (customer_id가 null인 회원 주문)만 이메일로 폴백한다. 게스트 주문은 두 값이 모두
     * null이라 어느 쪽 조건도 타지 않는다 — null == null로 남의 주문이 딸려오면 안 되므로
     * 각 분기에서 파라미터와 컬럼이 모두 not null인지 명시적으로 확인한다.
     */
    @Query("""
            select o from Order o
            where (:customerId is not null and o.customerId = :customerId)
               or (o.customerId is null and :customerEmail is not null and o.customerEmail = :customerEmail)
            order by o.createdAt desc
            """)
    List<Order> findMine(@Param("customerId") String customerId, @Param("customerEmail") String customerEmail);

    List<Order> findAllByOrderByCreatedAtDesc();

    /**
     * 회원별 구매확정 금액 합계. auth.api 의 등급 산정 배치가 클러스터 내부망으로 호출한다.
     *
     * <p><b>DELIVERED 만 센다.</b> CREATED/PAID/PREPARING/SHIPPED 는 아직 확정이 아니고
     * CANCELLED/REFUNDED 는 확정이 취소된 것이다. "결제했으니 등급이 오른다"로 만들면
     * 주문 후 환불을 반복해 등급을 올릴 수 있다.
     *
     * <p><b>게스트 주문(customerId is null)은 제외한다</b> — 귀속시킬 회원이 없다.
     * 레거시 이메일 폴백도 쓰지 않는다. 등급은 금전적 혜택이라 "아마 이 사람일 것"으로
     * 집계하면 안 된다.
     */
    @Query("""
            select o.customerId as customerId, sum(o.totalPrice) as confirmedAmount
            from Order o
            where o.customerId is not null
              and o.status = com.dh.order.domain.OrderStatus.DELIVERED
              and o.createdAt >= :since
            group by o.customerId
            """)
    List<PurchaseSummaryRow> sumConfirmedPurchasesSince(@Param("since") LocalDateTime since);

    /** 프로젝션 — 엔티티를 통째로 끌고 오지 않는다(회원 수만큼 커진다). */
    interface PurchaseSummaryRow {
        String getCustomerId();

        BigDecimal getConfirmedAmount();
    }
}
