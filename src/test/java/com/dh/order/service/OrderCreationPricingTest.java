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
    private com.dh.order.repository.ChannelRepository channelRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        productApiClient = mock(ProductApiClient.class);
        orderRepository = mock(OrderRepository.class);
        channelRepository = mock(com.dh.order.repository.ChannelRepository.class);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        com.dh.order.domain.Channel channel = new com.dh.order.domain.Channel("종합몰", "posselect.com");
        org.springframework.test.util.ReflectionTestUtils.setField(channel, "id", 1L);
        when(channelRepository.findById(1L)).thenReturn(java.util.Optional.of(channel));

        orderService = new OrderService(
                orderRepository,
                mock(ShipmentRepository.class),
                mock(PaymentRepository.class),
                mock(RefundRepository.class),
                mock(OrderNotificationService.class),
                productApiClient,
                channelRepository,
                mock(OrderPaymentFinalizer.class));
    }

    @Test
    void 주문_금액은_카탈로그_가격으로_계산된다() {
        가격을_돌려주도록(42L, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(42L, 2), 게스트());

        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격.multiply(BigDecimal.valueOf(2)));
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.price()).isEqualByComparingTo(카탈로그_가격));
    }

    @Test
    void 상품명과_productId도_카탈로그_값을_쓴다() {
        가격을_돌려주도록(42L, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(42L, 1), 게스트());

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(7L);
            assertThat(item.productName()).isEqualTo("게이밍 모니터 27인치");
        });
    }

    @Test
    void 카탈로그에_없는_variant는_주문이_거부된다() {
        when(productApiClient.resolveVariants(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(999L, 1), 게스트()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    @Test
    void 판매중지된_variant는_주문이_거부된다() {
        가격을_돌려주도록(42L, 카탈로그_가격, false);

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(42L, 1), 게스트()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    /**
     * product.api#97 - 1회 최대 구매 수량은 상품 단위다. 장바구니(product.api)를 거치지 않고 주문 API 를
     * 직접 불러도 막혀야 하므로 여기서도 본다. 같은 상품의 SKU 를 나눠 담아도 합계로 판정한다.
     */
    @Test
    void 상품별_최대_구매_수량을_넘으면_주문이_거부된다() {
        when(productApiClient.resolveVariants(anyList())).thenReturn(Map.of(
                41L, new ResolvedVariant(41L, 7L, "모니터 블랙", 카탈로그_가격, true, 2),
                42L, new ResolvedVariant(42L, 7L, "모니터 화이트", 카탈로그_가격, true, 2)));
        OrderCreateRequest 합계3 = new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가", null, null, null, null, null,
                List.of(new OrderItemRequest(41L, 1), new OrderItemRequest(42L, 2)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 합계3, 게스트()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.purchaseLimitExceeded");
    }

    @Test
    void 최대_구매_수량_이하면_통과한다() {
        when(productApiClient.resolveVariants(anyList())).thenReturn(Map.of(
                42L, new ResolvedVariant(42L, 7L, "모니터", 카탈로그_가격, true, 2)));

        OrderResponse response = orderService.createOrder(1L, 주문요청(42L, 2), 게스트());

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
    }

    private void 가격을_돌려주도록(Long variantId, BigDecimal price, boolean active) {
        when(productApiClient.resolveVariants(anyList())).thenReturn(
                Map.of(variantId, new ResolvedVariant(variantId, 7L, "게이밍 모니터 27인치", price, active, null)));
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
