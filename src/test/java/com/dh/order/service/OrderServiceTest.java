package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.order.config.ProductApiClient;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderItem;
import com.dh.order.domain.OrderStatus;
import com.dh.order.domain.Shipment;
import com.dh.order.domain.ShipmentStatus;
import com.dh.order.dto.OrderDtos.CreateShipmentRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.RefundResponse;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.payment.Payment;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.payment.RefundRepository;
import com.dh.order.repository.OrderRepository;
import com.dh.order.repository.ShipmentRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ShipmentRepository shipmentRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private OrderNotificationService notificationService;
    @Mock
    private ProductApiClient productApiClient;
    @Mock
    private com.dh.order.repository.ChannelRepository channelRepository;
    @Mock
    private OrderPaymentFinalizer orderPaymentFinalizer;

    @InjectMocks
    private OrderService orderService;

    private Order order;
    private Requester adminRequester;

    @BeforeEach
    void setUp() {
        order = new Order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", 1L);
        order.setTotalPrice(BigDecimal.valueOf(1000));
        OrderItem item = new OrderItem();
        item.setProductId(10L);
        item.setVariantId(20L);
        item.setProductName("테스트 상품");
        item.setPrice(BigDecimal.valueOf(1000));
        item.setQuantity(1);
        order.addItem(item);
        adminRequester = new Requester(null, null, null, true);
    }

    @Test
    void payOrder_ShouldTransitionToPaid_WhenStatusIsCreated() {
        // loadAccessibleWithItems가 findByIdWithItems를 쓴다 - JOIN FETCH로 items를 미리
        // 초기화해서 반환하는 전용 조회이고, 일반 findById와는 다른 메서드다.
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));
        // OrderPaymentFinalizer.markPaid는 별도 트랜잭션 빈 - 실제 구현처럼 order를 PAID로
        // 전이시키고 반환하도록 스텁한다.
        given(orderPaymentFinalizer.markPaid(1L)).willAnswer(invocation -> {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(java.time.LocalDateTime.now());
            return order;
        });

        OrderResponse response = orderService.payOrder(1L, adminRequester);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(response.status()).isEqualTo("PAID");

        // 재고 차감 -> 로컬 커밋 -> 알림 발송 순서가 지켜져야 한다(알림은 트랜잭션 밖,
        // 커밋 이후여야 함 - 롤백 불가능한 부수효과를 커넥션을 잡은 채로 하지 않기 위함).
        InOrder inOrder = Mockito.inOrder(productApiClient, orderPaymentFinalizer, notificationService);
        inOrder.verify(productApiClient).deductInventory(eq(1L), any());
        inOrder.verify(orderPaymentFinalizer).markPaid(1L);
        inOrder.verify(notificationService).notifyPaid(order);

        // 성공 경로에서는 보상 복원이 절대 호출되지 않는다.
        verify(productApiClient, never()).restoreInventory(any(), any());
    }

    @Test
    void payOrder_ShouldThrowException_WhenAlreadyPaid() {
        order.setStatus(OrderStatus.PAID);
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.alreadyPaid");

        // 초기 상태 가드에서 걸렸으므로 재고 차감 자체를 시도하지 않는다.
        verifyNoInteractions(productApiClient);
        verifyNoInteractions(orderPaymentFinalizer);
    }

    @Test
    void payOrder_ShouldNotCallDeductInventory_WhenAlreadyPaid() {
        // 위 테스트와 의도는 같지만 "차감을 시도하지 않는다"는 사실 자체를 명시적으로 검증한다
        // - 재고를 건드리지 않아야 아무 보상도 필요 없다.
        order.setStatus(OrderStatus.PAID);
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester));

        verify(productApiClient, never()).deductInventory(any(), any());
    }

    @Test
    void payOrder_ShouldRestoreInventory_WhenLocalCommitFailsAndOrderIsNotConcurrentlyPaid() {
        // 재고 차감은 성공했는데 로컬 커밋(PAID 전이 + Payment 저장)이 진짜 이유로 실패한
        // 경우 - 방금 나간 차감을 보상 복원해야 한다("payOrder에는 보상 로직이 없다"던
        // 결함을 고치는 게 이 테스트의 핵심).
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));
        given(orderPaymentFinalizer.markPaid(1L))
                .willThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        // 재확인 시점에도 여전히 CREATED다 - 동시 결제 경합에서 진 게 아니라 진짜 실패라는 뜻.
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.paymentConfirmationFailed");

        verify(productApiClient).deductInventory(eq(1L), any());
        verify(productApiClient).restoreInventory(eq(1L), any());
        verifyNoInteractions(notificationService);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void payOrder_ShouldNotRestoreInventory_WhenLostConcurrentPaymentRace() {
        // 두 요청이 동시에 CREATED를 읽고 둘 다 재고 차감까지 통과했다고 가정한다. 이긴 쪽이
        // 먼저 markPaid를 커밋해 주문이 PAID가 되고, 진 쪽은 payments.order_id UNIQUE
        // 제약(또는 markPaid 안의 재확인 가드)에 걸려 예외를 받는다 - 이 요청(진 쪽)은
        // 자신이 차감한 재고를 복원하면 안 된다. 그 차감은 이긴 쪽의 정당한 결제분이다.
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));
        given(orderPaymentFinalizer.markPaid(1L))
                .willThrow(new IllegalStateException("order already committed by a concurrent request: 1"));
        Order alreadyPaidByWinner = new Order();
        org.springframework.test.util.ReflectionTestUtils.setField(alreadyPaidByWinner, "id", 1L);
        alreadyPaidByWinner.setStatus(OrderStatus.PAID);
        given(orderRepository.findById(1L)).willReturn(Optional.of(alreadyPaidByWinner));

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.alreadyPaid");

        verify(productApiClient).deductInventory(eq(1L), any());
        verify(productApiClient, never()).restoreInventory(any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void payOrder_ShouldNotAttemptLocalCommitOrRestore_WhenDeductInventoryFails() {
        // 재고 차감 자체가 실패하면(품절/product.api 오류) 아무것도 확정되지 않았으므로
        // 로컬 커밋도, 보상 복원도 시도하지 않는다.
        given(orderRepository.findByIdWithItems(1L)).willReturn(Optional.of(order));
        org.mockito.BDDMockito.willThrow(new OrderStateException("order.outOfStock"))
                .given(productApiClient).deductInventory(eq(1L), any());

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.outOfStock");

        verifyNoInteractions(orderPaymentFinalizer);
        verify(productApiClient, never()).restoreInventory(any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void createShipment_ShouldTransitionToShipped_WhenStatusIsPaid() {
        order.setStatus(OrderStatus.PAID);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(shipmentRepository.findByOrderId(1L)).willReturn(Optional.empty());

        ShipmentResponse response = orderService.createShipment(1L, new CreateShipmentRequest("FedEx", "123"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(response.status()).isEqualTo("SHIPPED");
        verify(shipmentRepository).save(any(Shipment.class));
    }

    @Test
    void createShipment_ShouldThrowException_WhenNotPaid() {
        order.setStatus(OrderStatus.CREATED);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.createShipment(1L, new CreateShipmentRequest("FedEx", "123")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("결제 완료 상태의 주문만 배송을 등록할 수 있습니다");
    }

    @Test
    void markDelivered_ShouldTransitionToDelivered_WhenStatusIsShipped() {
        order.setStatus(OrderStatus.SHIPPED);
        Shipment shipment = new Shipment(order, "FedEx", "123");
        org.springframework.test.util.ReflectionTestUtils.setField(shipment, "status", ShipmentStatus.SHIPPED);
        given(shipmentRepository.findByOrderId(1L)).willReturn(Optional.of(shipment));

        ShipmentResponse response = orderService.markDelivered(1L);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(response.status()).isEqualTo("DELIVERED");
    }

    @Test
    void refundOrder_ShouldTransitionToRefunded_WhenStatusIsPaid() {
        order.setStatus(OrderStatus.PAID);
        Payment payment = new Payment(order, BigDecimal.valueOf(1000), "MOCK");
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

        RefundResponse response = orderService.refundOrder(1L, new RefundRequest("Customer request"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(refundRepository).save(any());
    }

    @Test
    void refundOrder_ShouldThrowException_WhenStatusIsCreated() {
        order.setStatus(OrderStatus.CREATED);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.refundOrder(1L, new RefundRequest("Customer request")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("환불할 수 없는 주문 상태입니다");
    }
}
