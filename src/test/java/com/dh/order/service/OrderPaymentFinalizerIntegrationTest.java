package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.domain.Channel;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.repository.ChannelRepository;
import com.dh.order.repository.OrderRepository;

/**
 * payOrder 트랜잭션 경계 재구성(#19)의 두 가지 핵심 주장을 실제 Postgres로 실측한다. Mockito
 * 단위 테스트(OrderServiceTest)는 리포지토리를 전부 목으로 대체하므로 이 두 가지를 구조적으로
 * 증명할 수 없다 - 이 저장소 AGENTS.md/캐논이 명시하는 공백이다.
 *
 * <ol>
 *   <li>{@code OrderRepository.findByIdWithItems}의 JOIN FETCH가 item이 2개 이상인 주문에서도
 *       정확히 Order 1건을 반환한다({@code distinct} 누락 시 NonUniqueResultException으로
 *       죽는다 - 리뷰에서 실제로 잡힌 회귀).</li>
 *   <li>같은 주문에 대해 {@code OrderPaymentFinalizer.markPaid}를 동시에 두 번 호출해도
 *       {@code payments.order_id} UNIQUE 제약(V3) 덕분에 정확히 한쪽만 성공하고, payments
 *       테이블에는 정확히 1행만 남는다 - "같은 키로 2회 호출 → 1회만 반영"을 실측하라는
 *       요구를 만족한다.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderPaymentFinalizerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private OrderPaymentFinalizer orderPaymentFinalizer;

    private Channel channel;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        channel = channelRepository.findById(1L).orElseThrow();
    }

    private Order newOrderWithItems(int itemCount) {
        Order order = new Order();
        order.setChannel(channel);
        order.setOrdererName("홍길동");
        order.setOrdererPhone("010-1234-5678");
        order.setShippingAddress("서울시 강남구 테헤란로 1");
        order.setStatus(OrderStatus.CREATED);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < itemCount; i++) {
            OrderItem item = new OrderItem();
            item.setProductId((long) (100 + i));
            item.setVariantId((long) (200 + i));
            item.setProductName("상품" + i);
            item.setPrice(BigDecimal.valueOf(1000));
            item.setQuantity(1);
            order.addItem(item);
            total = total.add(BigDecimal.valueOf(1000));
        }
        order.setTotalPrice(total);
        return orderRepository.saveAndFlush(order);
    }

    @Test
    @DisplayName("findByIdWithItems는 item이 2개 이상이어도 Order 1건만 반환한다 (distinct 회귀 방지)")
    void findByIdWithItems_ShouldReturnSingleOrder_WhenOrderHasMultipleItems() {
        Order saved = newOrderWithItems(3);

        Order loaded = orderRepository.findByIdWithItems(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getItems()).hasSize(3);
    }

    @Test
    @DisplayName("동시에 두 번 markPaid를 호출해도 정확히 한 번만 결제가 반영된다")
    void markPaid_ShouldApplyExactlyOnce_WhenCalledConcurrentlyForSameOrder() throws InterruptedException {
        Order saved = newOrderWithItems(1);
        Long orderId = saved.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        List<Future<?>> futures = List.of(
                executor.submit(() -> race(orderId, ready, go, successCount, failureCount)),
                executor.submit(() -> race(orderId, ready, go, successCount, failureCount)));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("worker thread failed unexpectedly", e);
            }
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);

        long paymentRows = paymentRepository.findByOrderId(orderId).stream().count();
        assertThat(paymentRows).isEqualTo(1);
    }

    private void race(Long orderId, CountDownLatch ready, CountDownLatch go,
            AtomicInteger successCount, AtomicInteger failureCount) {
        ready.countDown();
        try {
            go.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        try {
            orderPaymentFinalizer.markPaid(orderId);
            successCount.incrementAndGet();
        } catch (RuntimeException e) {
            failureCount.incrementAndGet();
        }
    }
}
