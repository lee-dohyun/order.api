package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ResolvedVariant;
import com.dh.order.domain.Order;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderItemRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.payment.PaymentRepository;
import com.dh.order.payment.RefundRepository;
import com.dh.order.repository.OrderRepository;
import com.dh.order.repository.ShipmentRepository;

/**
 * 주문 금액이 클라이언트가 아니라 product.api에서 온 값으로만 정해지는지 확인한다.
 * 예전엔 요청 본문의 price를 그대로 합산해서 임의 금액 주문이 가능했다 — Redmine posselect #232.
 */
class OrderCreationPricingTest {

    private static final BigDecimal 카탈로그_가격 = new BigDecimal("259000");

    private ProductApiClient productApiClient;
    private OrderRepository orderRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        productApiClient = mock(ProductApiClient.class);
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService = new OrderService(
                orderRepository,
                mock(ShipmentRepository.class),
                mock(PaymentRepository.class),
                mock(RefundRepository.class),
                mock(OrderNotificationService.class),
                productApiClient);
    }

    @Test
    void 주문_금액은_카탈로그_가격으로_계산된다() {
        가격을_돌려주도록(42L, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(주문요청(42L, 2), 게스트());

        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격.multiply(BigDecimal.valueOf(2)));
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.price()).isEqualByComparingTo(카탈로그_가격));
    }

    @Test
    void 상품명과_productId도_카탈로그_값을_쓴다() {
        가격을_돌려주도록(42L, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(주문요청(42L, 1), 게스트());

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(7L);
            assertThat(item.productName()).isEqualTo("게이밍 모니터 27인치");
        });
    }

    @Test
    void 카탈로그에_없는_variant는_주문이_거부된다() {
        when(productApiClient.resolveVariants(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> orderService.createOrder(주문요청(999L, 1), 게스트()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    @Test
    void 판매중지된_variant는_주문이_거부된다() {
        가격을_돌려주도록(42L, 카탈로그_가격, false);

        assertThatThrownBy(() -> orderService.createOrder(주문요청(42L, 1), 게스트()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    private void 가격을_돌려주도록(Long variantId, BigDecimal price, boolean active) {
        when(productApiClient.resolveVariants(anyList())).thenReturn(
                Map.of(variantId, new ResolvedVariant(variantId, 7L, "게이밍 모니터 27인치", price, active)));
    }

    private OrderCreateRequest 주문요청(Long variantId, int quantity) {
        return new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가",
                null, null, null, null, null,
                List.of(new OrderItemRequest(variantId, quantity)));
    }

    private Requester 게스트() {
        return new Requester(null, null, null, false);
    }
}
