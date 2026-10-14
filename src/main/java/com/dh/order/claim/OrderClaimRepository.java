package com.dh.order.claim;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, Long> {

    /** 한 주문의 클레임과 품목·주문 품목을 한 번에. 응답 조립과 금액 계산이 모두 품목을 본다. */
    @Query("""
            select distinct c from OrderClaim c
              left join fetch c.items ci left join fetch ci.orderItem
             where c.order.id = :orderId order by c.id
            """)
    List<OrderClaim> findByOrderIdWithItems(@Param("orderId") Long orderId);

    @Query("""
            select distinct c from OrderClaim c
              left join fetch c.items ci left join fetch ci.orderItem
             where c.id = :id
            """)
    Optional<OrderClaim> findByIdWithItems(@Param("id") Long id);

    Optional<OrderClaim> findByIdempotencyKey(String idempotencyKey);

    @Query("select c from OrderClaim c join fetch c.order where c.status = :status order by c.createdAt")
    List<OrderClaim> findByStatusWithOrder(@Param("status") ClaimStatus status);

    /** 완료된 취소·반품 수 — 금액 계산의 전제. 교환은 남은 상품 금액을 바꾸지 않아 세지 않는다. */
    @Query("select count(c) from OrderClaim c where c.order.id = :orderId and c.status = com.dh.order.claim.ClaimStatus.COMPLETED"
            + " and c.type in (com.dh.order.claim.ClaimType.CANCEL, com.dh.order.claim.ClaimType.RETURN)")
    long countCompleted(@Param("orderId") Long orderId);
}
