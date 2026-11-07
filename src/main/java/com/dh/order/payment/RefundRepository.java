package com.dh.order.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** 주문 전체 환불(gateway#329 상세 화면). refundOrder 가 REFUNDED 로 바꾸므로 주문당 한 건이지만, 최신 것을 고른다. */
    Optional<Refund> findFirstByPaymentOrderIdOrderByIdDesc(Long orderId);
}
