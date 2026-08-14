package com.dh.order.repository;

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
}
