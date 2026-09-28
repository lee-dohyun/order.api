package com.dh.order.service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dh.order.domain.Order;
import com.dh.order.domain.OrderStatus;
import com.dh.order.payment.Payment;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.repository.OrderRepository;

/**
 * {@code OrderService.payOrder}의 "로컬 커밋" 단계만 담당하는 좁은 트랜잭션 경계다.
 *
 * <p>payOrder는 재고 차감(원격 HTTP, 5초 타임아웃) 동안 DB 커넥션을 붙잡지 않으려고
 * {@code Propagation.NOT_SUPPORTED}로 도는데, 그 상태에서 같은 클래스 안에 이 로직을
 * {@code @Transactional} 메서드로만 선언하면 self-invocation이라 Spring 프록시를 타지
 * 않아 트랜잭션이 전혀 걸리지 않는다(AGENTS.md가 "새 쓰기 경로는 가급적 별도 서비스로
 * 분리한다"고 권고하는 이유가 정확히 이거다). 그래서 별도 빈으로 뺐다.
 *
 * <p>주문 상태 전이(PAID)와 Payment 저장을 하나의 트랜잭션으로 묶어야 "재고는 빠졌는데
 * 결제 기록만 없는" 상태가 나지 않는다.
 */
@Component
public class OrderPaymentFinalizer {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public OrderPaymentFinalizer(OrderRepository orderRepository, PaymentRepository paymentRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * payOrder가 처음 CREATED를 확인한 뒤 재고 차감(원격 호출)이 끝날 때까지는 트랜잭션이
     * 없으므로, 그 사이 다른 요청이 먼저 결제를 끝냈을 수 있다. 여기서 행을 새로 읽어 상태를
     * 다시 확인해 그 경합 창을 좁힌다 - 완전히 없애지는 못한다(두 요청이 여기 진입도 동시에
     * 할 수 있다). 최종 방어는 {@code payments.order_id} UNIQUE 제약(V3)이고, 두 경우 모두
     * payOrder 쪽에서 "이미 결제됨"과 "진짜 실패"를 구분해 처리한다.
     *
     * @throws NoSuchElementException 주문이 없으면(정상 흐름에서는 사실상 발생하지 않는다)
     * @throws IllegalStateException 이미 CREATED가 아니면(동시 결제 경합에서 진 경우)
     */
    @Transactional
    public Order markPaid(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new NoSuchElementException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new IllegalStateException("order already committed by a concurrent request: " + orderId);
        }
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        paymentRepository.save(new Payment(order, order.getTotalPrice(), "MOCK"));
        return order;
    }
}
