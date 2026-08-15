package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.order.config.ProductApiClient;
import com.dh.order.domain.Order;
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

    @InjectMocks
    private OrderService orderService;

    private Order order;
    private Requester adminRequester;

    @BeforeEach
    void setUp() {
        order = new Order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", 1L);
        order.setTotalPrice(BigDecimal.valueOf(1000));
        adminRequester = new Requester(null, null, null, true);
    }

    @Test
    void payOrder_ShouldTransitionToPaid_WhenStatusIsCreated() {
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.payOrder(1L, adminRequester);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(response.status()).isEqualTo("PAID");
        verify(productApiClient).deductInventory(eq(1L), any());
        verify(paymentRepository).save(any(Payment.class));
        verify(notificationService).notifyPaid(order);
    }

    @Test
    void payOrder_ShouldThrowException_WhenAlreadyPaid() {
        order.setStatus(OrderStatus.PAID);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.payOrder(1L, adminRequester))
                .isInstanceOf(OrderStateException.class)
                .hasMessageContaining("order.alreadyPaid");
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
