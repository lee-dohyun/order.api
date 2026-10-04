package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ResolvedOffer;
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
 * 주문 금액·상품·판매자가 클라이언트가 아니라 product.api 가 확정한 오퍼 값으로만 정해지는지 확인한다.
 * 예전엔 요청 본문의 price 를 그대로 합산해서 임의 금액 주문이 가능했다 — Redmine posselect #232.
 *
 * <p>order.api#14 부터 확정 단위가 variant 가격에서 <b>오퍼</b>로 바뀌었다. 품목은 offerId 로도,
 * (호환용) variantId 로도 올 수 있고, variantId 만 오면 product.api 가 그 SKU 의 대표 오퍼를 고른다.
 */
class OrderCreationPricingTest {

    private static final BigDecimal 카탈로그_가격 = new BigDecimal("259000");
    private static final Long SKU = 42L;
    private static final Long 대표_오퍼 = 501L;

    private static final String 회원_SUB = "2f1c7a3e-0000-4000-8000-000000000001";

    private ProductApiClient productApiClient;
    private AuthApiClient authApiClient;
    private OrderRepository orderRepository;
    private com.dh.order.repository.ChannelRepository channelRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        productApiClient = mock(ProductApiClient.class);
        authApiClient = mock(AuthApiClient.class);
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
                mock(OrderPaymentFinalizer.class),
                mock(InventoryCompensationStore.class),
                mock(InventoryCompensator.class),
                authApiClient);
    }

    // ------------------------------------------------------------------ variantId(호환) 경로

    @Test
    void 주문_금액은_카탈로그_가격으로_계산된다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 2), 게스트(), null);

        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격.multiply(BigDecimal.valueOf(2)));
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.price()).isEqualByComparingTo(카탈로그_가격));
    }

    @Test
    void 상품명과_productId도_카탈로그_값을_쓴다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(7L);
            assertThat(item.productName()).isEqualTo("게이밍 모니터 27인치");
        });
    }

    @Test
    void 카탈로그에_없는_variant는_주문이_거부된다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(999L, 1), 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    @Test
    void 판매중지된_variant는_주문이_거부된다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, false);

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    /**
     * product.api#97 - 1회 최대 구매 수량은 상품 단위다. 장바구니(product.api)를 거치지 않고 주문 API 를
     * 직접 불러도 막혀야 하므로 여기서도 본다. 같은 상품의 SKU 를 나눠 담아도 합계로 판정한다.
     *
     * <p>order.api#14 로 확정 단위가 오퍼가 된 뒤에는 한도의 출처가 오퍼 응답의
     * {@code maxPurchaseQuantity} 다(product.api#108 이 그 필드를 실어 준다).
     */
    @Test
    void 상품별_최대_구매_수량을_넘으면_주문이_거부된다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                41L, 오퍼(511L, 41L, 카탈로그_가격, true, 1L, "포스셀렉트", 2),
                42L, 오퍼(512L, 42L, 카탈로그_가격, true, 1L, "포스셀렉트", 2)));
        OrderCreateRequest 합계3 = new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가", null, null, null, null, null,
                List.of(new OrderItemRequest(null, 41L, 1), new OrderItemRequest(null, 42L, 2)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 합계3, 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.purchaseLimitExceeded");
    }

    @Test
    void 최대_구매_수량_이하면_통과한다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 오퍼(대표_오퍼, SKU, 카탈로그_가격, true, 1L, "포스셀렉트", 2)));

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 2), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
    }

    /**
     * order.api#47 - 재고보다 많은 수량은 주문을 만들 때 거른다. 전에는 결제할 수 없는 주문이 CREATED 로
     * 만들어지고(재고 58 에 99,999개, 약 21.9억 원 - 운영 실측) 결제 단계에서야 막혔다.
     * 여기서는 조회만 하고 차감은 여전히 결제 때 한다.
     */
    @Test
    void 재고보다_많이_담으면_주문이_거부되고_남은_수량을_알려준다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 재고오퍼(대표_오퍼, SKU, true, 58)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(SKU, 59), 게스트(), null))
                .isInstanceOfSatisfying(OrderStateException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("order.insufficientStock");
                    assertThat(e.getMessageArgs()).containsExactly("게이밍 모니터 27인치", 58);
                });
    }

    @Test
    void 재고와_같은_수량까지는_통과한다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 재고오퍼(대표_오퍼, SKU, true, 58)));

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 58), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(58));
    }

    @Test
    void 재고가_0이면_품절로_거부된다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 재고오퍼(대표_오퍼, SKU, true, 0)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null))
                .isInstanceOfSatisfying(OrderStateException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("order.soldOut");
                    assertThat(e.getMessageArgs()).containsExactly("게이밍 모니터 27인치");
                });
    }

    @Test
    void 같은_SKU를_여러_줄로_나눠_담아도_합계로_재고를_본다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of(
                601L, 재고오퍼(601L, 43L, true, 5)));
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                43L, 재고오퍼(602L, 43L, true, 5)));
        OrderCreateRequest 합계6 = new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가", null, null, null, null, null,
                List.of(new OrderItemRequest(601L, null, 3), new OrderItemRequest(null, 43L, 3)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 합계6, 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.insufficientStock");
    }

    @Test
    void 재고를_모르면_검사하지_않는다() {
        // 재고 필드를 싣지 않는 product.api(배포 순서가 어긋난 동안)와도 주문은 만들어져야 한다.
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 재고오퍼(대표_오퍼, SKU, true, null)));

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 99_999), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(99_999));
    }

    @Test
    void 판매하지_않는_상품은_재고가_없어도_판매_불가로_안내한다() {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                SKU, 재고오퍼(대표_오퍼, SKU, false, 0)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    @Test
    void offerId_경로에서도_최대_구매_수량이_걸린다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of(
                601L, 오퍼(601L, 43L, 카탈로그_가격, true, 7L, "테스트 공급사", 2)));

        assertThatThrownBy(() -> orderService.createOrder(1L, 오퍼주문요청(601L, 3), 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.purchaseLimitExceeded");
    }

    @Test
    void variantId로_온_품목도_product_api가_고른_대표_오퍼를_기록한다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.offerId()).isEqualTo(대표_오퍼);
            assertThat(item.variantId()).isEqualTo(SKU);
        });
    }

    // ------------------------------------------------------------------ offerId 경로

    @Test
    void offerId로_온_품목은_그_오퍼의_가격과_판매자로_확정한다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of(
                601L, 오퍼(601L, 43L, new BigDecimal("199000"), true, 7L, "테스트 공급사")));

        OrderResponse response = orderService.createOrder(1L, 오퍼주문요청(601L, 2), 게스트(), null);

        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("398000"));
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.offerId()).isEqualTo(601L);
            assertThat(item.variantId()).as("재고 차감 기준 키는 오퍼가 가리키는 SKU").isEqualTo(43L);
            assertThat(item.price()).isEqualByComparingTo(new BigDecimal("199000"));
        });
    }

    @Test
    void 판매자_스냅샷은_상수가_아니라_확정된_오퍼의_판매자다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of(
                601L, 오퍼(601L, 43L, new BigDecimal("199000"), true, 7L, "테스트 공급사")));

        OrderResponse response = orderService.createOrder(1L, 오퍼주문요청(601L, 1), 게스트(), null);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.sellerId()).isEqualTo(7L);
            assertThat(item.sellerName()).isEqualTo("테스트 공급사");
        });
    }

    @Test
    void 없는_offerId는_주문이_거부된다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> orderService.createOrder(1L, 오퍼주문요청(9999L, 1), 게스트(), null))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.itemUnavailable");
    }

    @Test
    void offerId와_variantId가_섞인_주문도_품목마다_따로_확정한다() {
        when(productApiClient.resolveOffers(anyList())).thenReturn(Map.of(
                601L, 오퍼(601L, 11L, new BigDecimal("1000"), true, 1L, "포스셀렉트")));
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(Map.of(
                22L, 오퍼(602L, 22L, new BigDecimal("2000"), true, 1L, "포스셀렉트")));

        OrderCreateRequest request = new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가",
                null, null, null, null, null,
                List.of(new OrderItemRequest(601L, null, 2), new OrderItemRequest(null, 22L, 3)));

        OrderResponse response = orderService.createOrder(1L, request, 게스트(), null);

        assertThat(response.totalPrice()).isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(response.items()).extracting(i -> i.offerId()).containsExactly(601L, 602L);
    }

    // ------------------------------------------------------------------ 등급 할인 (gateway#82)

    @Test
    void 회원_등급_할인율만큼_결제_금액이_줄고_근거가_주문에_남는다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);
        등급을_돌려주도록("GOLD", "5.00");

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 2), 회원(), null);

        assertThat(response.subtotalPrice()).isEqualByComparingTo("518000");
        assertThat(response.discountAmount()).isEqualByComparingTo("25900");
        assertThat(response.totalPrice()).isEqualByComparingTo("492100");
        assertThat(response.gradeCode()).isEqualTo("GOLD");
        assertThat(response.gradeDiscountRate()).isEqualByComparingTo("5.00");
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.price()).as("품목 단가는 할인 전 가격 그대로")
                        .isEqualByComparingTo(카탈로그_가격));
    }

    @Test
    void 할인액은_원_미만을_버린다() {
        대표오퍼를_돌려주도록(SKU, new BigDecimal("990"), true);
        등급을_돌려주도록("SILVER", "2.00");

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 회원(), null);

        // 990 × 2% = 19.8 → 19. 반올림(20)하면 약속한 할인율보다 더 깎는다.
        assertThat(response.discountAmount()).isEqualByComparingTo("19");
        assertThat(response.totalPrice()).isEqualByComparingTo("971");
    }

    @Test
    void 게스트_주문은_등급을_조회하지_않고_할인도_없다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 게스트(), null);

        verifyNoInteractions(authApiClient);
        assertThat(response.discountAmount()).isEqualByComparingTo("0");
        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격);
        assertThat(response.gradeCode()).isNull();
    }

    @Test
    void 이메일만_있는_요청자는_등급_할인을_받지_않는다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);

        OrderResponse response = orderService.createOrder(
                1L, 주문요청(SKU, 1), new Requester(null, "someone@example.com", null, false), null);

        verifyNoInteractions(authApiClient);
        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격);
    }

    @Test
    void 등급을_확인하지_못하면_할인_없이_주문은_만들어진다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);
        when(authApiClient.findMemberGrade(회원_SUB)).thenReturn(Optional.empty());

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 회원(), null);

        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격);
        assertThat(response.discountAmount()).isEqualByComparingTo("0");
        assertThat(response.gradeCode()).isNull();
    }

    @Test
    void 할인율이_0인_등급은_등급_근거를_남기지_않는다() {
        대표오퍼를_돌려주도록(SKU, 카탈로그_가격, true);
        등급을_돌려주도록("GENERAL", "0.00");

        OrderResponse response = orderService.createOrder(1L, 주문요청(SKU, 1), 회원(), null);

        assertThat(response.totalPrice()).isEqualByComparingTo(카탈로그_가격);
        assertThat(response.gradeCode()).as("grade_code 는 할인이 적용된 주문에만 채운다").isNull();
    }

    // ------------------------------------------------------------------ 요청 규칙

    @Test
    void 품목은_offerId와_variantId_중_정확히_하나여야_한다() {
        assertThat(new OrderItemRequest(1L, null, 1).isExactlyOneTarget()).isTrue();
        assertThat(new OrderItemRequest(null, 2L, 1).isExactlyOneTarget()).isTrue();
        assertThat(new OrderItemRequest(1L, 2L, 1).isExactlyOneTarget())
                .as("둘 다 주면 어느 오퍼로 샀는지 모호하다").isFalse();
        assertThat(new OrderItemRequest(null, null, 1).isExactlyOneTarget())
                .as("둘 다 없으면 무엇을 샀는지 알 수 없다").isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static ResolvedOffer 오퍼(Long offerId, Long variantId, BigDecimal price, boolean active,
            Long sellerId, String sellerName) {
        return 오퍼(offerId, variantId, price, active, sellerId, sellerName, null);
    }

    /** 상품 id 는 7L 로 고정이다 — 같은 상품의 SKU 를 여러 개 만들어 수량 한도 합산을 보기 위함이다. */
    private static ResolvedOffer 오퍼(Long offerId, Long variantId, BigDecimal price, boolean active,
            Long sellerId, String sellerName, Integer maxPurchaseQuantity) {
        return new ResolvedOffer(offerId, variantId, 7L, "게이밍 모니터 27인치", sellerId, sellerName,
                price, null, false, null, active, maxPurchaseQuantity, null);
    }

    /** 재고를 아는 오퍼. 구매 수량 한도는 없다. */
    private static ResolvedOffer 재고오퍼(Long offerId, Long variantId, boolean active, Integer stockQuantity) {
        return new ResolvedOffer(offerId, variantId, 7L, "게이밍 모니터 27인치", 1L, "포스셀렉트",
                카탈로그_가격, null, false, null, active, null, stockQuantity);
    }

    private void 대표오퍼를_돌려주도록(Long variantId, BigDecimal price, boolean active) {
        when(productApiClient.resolveFeaturedOffersByVariant(anyList())).thenReturn(
                Map.of(variantId, 오퍼(대표_오퍼, variantId, price, active, 1L, "포스셀렉트")));
    }

    private OrderCreateRequest 주문요청(Long variantId, int quantity) {
        return new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가",
                null, null, null, null, null,
                List.of(new OrderItemRequest(null, variantId, quantity)));
    }

    private OrderCreateRequest 오퍼주문요청(Long offerId, int quantity) {
        return new OrderCreateRequest(
                "홍길동", "010-1234-5678", "서울시 어딘가",
                null, null, null, null, null,
                List.of(new OrderItemRequest(offerId, null, quantity)));
    }

    private void 등급을_돌려주도록(String code, String discountRate) {
        when(authApiClient.findMemberGrade(회원_SUB)).thenReturn(
                Optional.of(new AuthApiClient.MemberGrade(code, code, new BigDecimal(discountRate))));
    }

    private Requester 회원() {
        return new Requester(회원_SUB, "member@example.com", null, false);
    }

    private Requester 게스트() {
        return new Requester(null, null, null, false);
    }
}
