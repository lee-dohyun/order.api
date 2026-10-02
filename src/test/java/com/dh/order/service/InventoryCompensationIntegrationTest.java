package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.config.ProductApiClient;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.repository.ChannelRepository;
import com.dh.order.repository.OrderRepository;

/**
 * 재고 보상의 미결 기록과 재시도(#34)를 실제 Postgres 로 실측한다. 상태 전이가 전부 조건부 UPDATE 라
 * 목 리포지토리로는 "누가 그 행을 얻었는가"를 증명할 수 없다. product.api 만 목이다 — 이 테스트가 보는
 * 것은 "복원 호출이 언제, 몇 번 나가는가"와 그 결과로 남는 행이다.
 *
 * <p>스케줄러는 꺼 두고({@code src/test/resources/application.properties}) {@code sweep()} 을 직접 부른다. 방치 판정은 {@code touched_at} 을
 * 과거로 밀어 재현한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryCompensationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @MockitoBean
    private ProductApiClient productApiClient;
    @MockitoBean
    private OrderNotificationService notificationService;

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderPaymentFinalizer orderPaymentFinalizer;
    @Autowired
    private InventoryCompensationStore store;
    @Autowired
    private InventoryCompensator compensator;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Requester ADMIN = new Requester(null, null, null, true);

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM inventory_compensations");
        jdbc.update("DELETE FROM refunds");
        jdbc.update("DELETE FROM payments");
        orderRepository.deleteAll();
    }

    private Long newOrder() {
        Order order = new Order();
        order.setChannel(channelRepository.findById(1L).orElseThrow());
        order.setOrdererName("홍길동");
        order.setOrdererPhone("010-1234-5678");
        order.setShippingAddress("서울시 강남구 테헤란로 1");
        order.setStatus(OrderStatus.CREATED);
        OrderItem item = new OrderItem();
        item.setProductId(100L);
        item.setVariantId(200L);
        item.setProductName("상품");
        item.setPrice(BigDecimal.valueOf(1000));
        item.setQuantity(3);
        item.setSellerId(1L);
        item.setSellerName("포스셀렉트");
        order.addItem(item);
        order.setTotalPrice(BigDecimal.valueOf(3000));
        return orderRepository.saveAndFlush(order).getId();
    }

    private Map<String, Object> row(Long orderId, String kind) {
        return jdbc.queryForMap(
                "SELECT status, attempts, last_error FROM inventory_compensations WHERE order_id = ? AND kind = ?",
                orderId, kind);
    }

    /** 그 행을 건드린 지 오래된 것처럼 만든다. */
    private void age(Long orderId, String interval) {
        jdbc.update("UPDATE inventory_compensations SET touched_at = touched_at - ?::interval WHERE order_id = ?",
                interval, orderId);
    }

    @Test
    @DisplayName("차감 뒤 결제 확정 전에 죽은 시도는 스케줄러가 재고를 되돌린다 — 한 번만")
    void sweep_RestoresAbandonedPaymentAttemptOnce() {
        Long orderId = newOrder();
        // payOrder 가 beginPayment → deductInventory 까지 하고 파드가 죽은 상태.
        assertThat(store.beginPayment(orderId)).isTrue();
        age(orderId, "10 minutes");

        compensator.sweep();
        compensator.sweep();

        verify(productApiClient, times(1)).restoreInventory(eq(orderId), any());
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "DONE");
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("진행 중인 결제 시도는 건드리지 않는다")
    void sweep_LeavesFreshPaymentAttemptAlone() {
        Long orderId = newOrder();
        assertThat(store.beginPayment(orderId)).isTrue();

        compensator.sweep();

        verify(productApiClient, never()).restoreInventory(any(), any());
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "WATCHING");
    }

    @Test
    @DisplayName("결제가 확정되면 시도 행이 함께 닫히고, 나중에 복원되지 않는다")
    void payOrder_ClosesAttemptAtomicallyWithPaid() {
        Long orderId = newOrder();

        orderService.payOrder(orderId, ADMIN);

        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "DONE");
        age(orderId, "10 minutes");
        compensator.sweep();
        verify(productApiClient, never()).restoreInventory(any(), any());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("복원이 시작된 주문은 결제를 받지도, 확정하지도 않는다")
    void restoringOrder_RejectsPaymentAndCommit() {
        Long orderId = newOrder();
        assertThat(store.beginPayment(orderId)).isTrue();
        age(orderId, "10 minutes");
        // 복원이 계속 실패해 RESTORING 으로 남는 상황.
        willThrow(new OrderStateException("order.inventoryUnavailable"))
                .given(productApiClient).restoreInventory(eq(orderId), any());
        compensator.sweep();
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "RESTORING").containsEntry("attempts", 1);

        // 새 결제 요청: 차감 자체를 보내지 않는다.
        assertThatThrownBy(() -> orderService.payOrder(orderId, ADMIN))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.inventoryUnavailable");
        verify(productApiClient, never()).deductInventory(any(), any());

        // 이미 차감까지 보낸 느린 요청이 뒤늦게 확정하려는 경우: 확정이 거부되고 아무것도 커밋되지 않는다.
        assertThatThrownBy(() -> orderPaymentFinalizer.markPaid(orderId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class, orderId))
                .isZero();
    }

    @Test
    @DisplayName("환불의 재고 복원이 실패하면 행이 남고, 간격이 지난 뒤 다시 시도해 닫는다")
    void refundRestore_RetriesUntilItSucceeds() {
        Long orderId = newOrder();
        orderService.payOrder(orderId, ADMIN);

        willThrow(new OrderStateException("order.inventoryUnavailable"))
                .given(productApiClient).restoreInventory(eq(orderId), any());
        orderService.refundOrder(orderId, new RefundRequest("고객 요청"));
        // 예약 행은 REFUNDED 전이와 같은 트랜잭션에서 이미 커밋돼 있다 - 아래 호출이 없어도(죽어도) 남는다.
        assertThat(row(orderId, "REFUND")).containsEntry("status", "RESTORING").containsEntry("attempts", 0);

        compensator.restoreRefundNow(orderId);
        assertThat(row(orderId, "REFUND")).containsEntry("status", "RESTORING").containsEntry("attempts", 1);
        assertThat((String) row(orderId, "REFUND").get("last_error")).contains("order.inventoryUnavailable");

        // 재시도 간격 전에는 다시 부르지 않는다.
        compensator.sweep();
        verify(productApiClient, times(1)).restoreInventory(eq(orderId), any());

        // product.api 가 돌아온 뒤.
        willDoNothing().given(productApiClient).restoreInventory(eq(orderId), any());
        age(orderId, "10 minutes");
        compensator.sweep();

        verify(productApiClient, times(2)).restoreInventory(eq(orderId), any());
        assertThat(row(orderId, "REFUND")).containsEntry("status", "DONE");
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("복원이 끝난 주문은 다시 결제할 수 있다")
    void payOrder_WorksAgainAfterRestoreCompletes() {
        Long orderId = newOrder();
        assertThat(store.beginPayment(orderId)).isTrue();
        age(orderId, "10 minutes");
        compensator.sweep();
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "DONE");

        orderService.payOrder(orderId, ADMIN);

        verify(productApiClient).deductInventory(eq(orderId), any());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "DONE");
    }

    @Test
    @DisplayName("결제는 확정됐는데 열린 채 남은 시도 행은 복원 없이 닫는다")
    void sweep_ClosesStrayAttemptOfPaidOrderWithoutRestoring() {
        Long orderId = newOrder();
        orderService.payOrder(orderId, ADMIN);
        // 동시 결제에서 진 요청이 확정 이후에 beginPayment 를 다시 찍은 상황.
        assertThat(store.beginPayment(orderId)).isTrue();
        age(orderId, "10 minutes");

        compensator.sweep();

        verify(productApiClient, never()).restoreInventory(any(), any());
        assertThat(row(orderId, "PAYMENT")).containsEntry("status", "DONE");
    }
}
