package com.dh.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    @Query("select distinct o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

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
