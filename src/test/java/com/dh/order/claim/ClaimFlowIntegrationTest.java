package com.dh.order.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

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

import com.dh.order.claim.ClaimDtos.ClaimCreateRequest;
import com.dh.order.claim.ClaimDtos.ClaimItemRequest;
import com.dh.order.claim.ClaimDtos.ClaimResponse;
import com.dh.order.config.AuthApiClient;
import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ShippingQuote;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.service.OrderNotificationService;
import com.dh.order.service.OrderService;
import com.dh.order.service.OrderStateException;

/**
 * 고객 상품별 취소·반품(gateway#320)을 실제 Postgres 로 끝까지. 금액·수량·상태가 DB 에 어떻게 남는지가 검증 대상이라
 * (캐논 §3: 정합성 변경은 단위 테스트로 성립하지 않는다) 결제·환불·클레임 행을 직접 센다. product.api 는 목이다.
 *
 * <p>픽스처 주문: 상품 A 30,000 × 1 + B 20,000 × 1 = 50,000, 등급 할인 1,000, 배송비 0 → 결제 49,000.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ClaimFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @MockitoBean
    private ProductApiClient productApiClient;
    @MockitoBean
    private AuthApiClient authApiClient;
    @MockitoBean
    private OrderNotificationService notificationService;

    @Autowired
    private ClaimService claimService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String OWNER = "member-1";
    private static final Requester ME = Requester.of(OWNER, null, null, false);

    private long orderId;
    private long itemA;
    private long itemB;

    @BeforeEach
    void setUp() {
        for (String t : List.of("inventory_compensations", "refunds", "order_claim_items", "order_claims", "payments",
                "shipments", "order_items", "orders")) {
            jdbc.update("DELETE FROM " + t);
        }
        given(productApiClient.quoteShipping(anyList())).willReturn(new ShippingQuote(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /** 상태별 픽스처. paid 면 결제 행, shipped/delivered 면 배송 행까지 만든다. */
    private void order(String status, LocalDateTime deliveredAt) {
        orderId = jdbc.queryForObject("""
                INSERT INTO orders (status, total_price, discount_amount, shipping_fee, created_at, channel_id, customer_id,
                                    orderer_name, orderer_phone, shipping_address, recipient_name, recipient_phone)
                VALUES (?, 49000, 1000, 0, now(), 1, ?, '주문자', '01000000000', '주소', '수령인', '01000000000')
                RETURNING id""", Long.class, status, OWNER);
        itemA = item(101L, 30000);
        itemB = item(102L, 20000);
        if (!status.equals("CREATED")) {
            jdbc.update("INSERT INTO payments (order_id, amount, method, status, paid_at, created_at) VALUES (?, 49000, 'MOCK', 'COMPLETED', now(), now())", orderId);
        }
        if (status.equals("SHIPPED") || status.equals("DELIVERED")) {
            jdbc.update("""
                    INSERT INTO shipments (order_id, carrier, tracking_number, status, created_at, shipped_at, delivered_at)
                    VALUES (?, 'CJ대한통운', '1', ?, now(), now(), ?)""",
                    orderId, status.equals("DELIVERED") ? "DELIVERED" : "SHIPPED",
                    deliveredAt == null ? null : Timestamp.valueOf(deliveredAt));
        }
    }

    private long item(long variantId, int price) {
        return jdbc.queryForObject("""
                INSERT INTO order_items (order_id, product_id, variant_id, product_name, price, quantity, seller_id, seller_name, offer_id)
                VALUES (?, ?, ?, ?, ?, 1, 1, '포스셀렉트', ?) RETURNING id""",
                Long.class, orderId, variantId, variantId, "상품" + variantId, price, variantId + 1000);
    }

    private static ClaimCreateRequest cancel(long... itemIds) {
        return new ClaimCreateRequest(ClaimType.CANCEL, null, null,
                java.util.Arrays.stream(itemIds).mapToObj(id -> new ClaimItemRequest(id, 1)).toList());
    }

    private static ClaimCreateRequest returning(ClaimReason reason, long itemId) {
        return new ClaimCreateRequest(ClaimType.RETURN, reason, "사이즈가 안 맞아요", List.of(new ClaimItemRequest(itemId, 1)));
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private BigDecimal refundedTotal() {
        return jdbc.queryForObject("SELECT coalesce(sum(r.amount), 0) FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE p.order_id = ?",
                BigDecimal.class, orderId);
    }

    private int claimed(long itemId) {
        return jdbc.queryForObject("SELECT claimed_quantity FROM order_items WHERE id = ?", Integer.class, itemId);
    }

    @Test
    @DisplayName("출고 전 상품 하나를 취소하면 즉시 그 몫만 환불되고, 그 품목만 재고 복원이 예약·호출된다")
    void partialCancelBeforeShipment() {
        order("PAID", null);

        ClaimResponse claim = claimService.request(orderId, ME, cancel(itemB), null);

        assertThat(claim.status()).isEqualTo("COMPLETED");
        assertThat(claim.refundAmount()).isEqualByComparingTo("19600"); // 20,000 − 할인 몫 400
        assertThat(refundedTotal()).isEqualByComparingTo("19600");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(claimed(itemB)).isEqualTo(1);
        assertThat(claimed(itemA)).isZero();
        verify(productApiClient).restorePartial(eq(orderId), eq("claim-" + claim.id()),
                eq(List.of(new ProductApiClient.PartialRestoreItem(102L, 1))));
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_compensations WHERE claim_id = ?", String.class, claim.id()))
                .isEqualTo("DONE");
    }

    @Test
    @DisplayName("나머지까지 취소하면 환불 합계가 결제 금액과 같고 주문은 CANCELLED")
    void cancelAllInTwoSteps() {
        order("PAID", null);
        claimService.request(orderId, ME, cancel(itemB), null);
        ClaimResponse last = claimService.request(orderId, ME, cancel(itemA), null);

        assertThat(last.refundAmount()).isEqualByComparingTo("29400");
        assertThat(refundedTotal()).isEqualByComparingTo("49000");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("남은 상품이 무료배송 기준 아래로 떨어지면 면제됐던 배송비를 환불액에서 뺀다")
    void shippingRecalculatedOnPartialCancel() {
        order("PAID", null);
        given(productApiClient.quoteShipping(anyList())).willReturn(new ShippingQuote(new BigDecimal("3000"), BigDecimal.ZERO));

        ClaimResponse claim = claimService.request(orderId, ME, cancel(itemB), null);

        assertThat(claim.shippingRefund()).isEqualByComparingTo("-3000");
        assertThat(claim.refundAmount()).isEqualByComparingTo("16600");
    }

    @Test
    @DisplayName("같은 품목을 남은 수량보다 많이 신청하면 거부하고, 수량을 잡지 않는다")
    void overClaimRejected() {
        order("PAID", null);
        claimService.request(orderId, ME, cancel(itemB), null);

        assertThatThrownBy(() -> claimService.request(orderId, ME, cancel(itemB), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.claimQuantityExceeded");
        assertThat(claimed(itemB)).isEqualTo(1);
        assertThat(refundedTotal()).isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("같은 멱등 키의 재시도는 새 신청·환불을 만들지 않는다")
    void idempotentRetry() {
        order("PAID", null);
        ClaimResponse first = claimService.request(orderId, ME, cancel(itemB), "claim-key-0000000001");
        ClaimResponse retry = claimService.request(orderId, ME, cancel(itemB), "claim-key-0000000001");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_claims", Integer.class)).isEqualTo(1);
        assertThat(refundedTotal()).isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("남의 주문은 404")
    void othersOrderIs404() {
        order("PAID", null);

        assertThatThrownBy(() -> claimService.request(orderId, Requester.of("member-2", null, null, false), cancel(itemB), null))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(claimed(itemB)).isZero();
    }

    @Test
    @DisplayName("출고된 주문은 취소할 수 없다")
    void cancelAfterShipmentRejected() {
        order("SHIPPED", null);

        assertThatThrownBy(() -> claimService.request(orderId, ME, cancel(itemB), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.claimNotAllowed");
    }

    @Test
    @DisplayName("결제 전 주문은 부분 취소를 받지 않고, 전체 취소는 환불 없이 CANCELLED")
    void unpaidOrder() {
        order("CREATED", null);

        assertThatThrownBy(() -> claimService.request(orderId, ME, cancel(itemB), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.cancelWholeOrderOnly");

        ClaimResponse whole = claimService.request(orderId, ME, cancel(itemA, itemB), null);
        assertThat(whole.status()).isEqualTo("COMPLETED");
        assertThat(whole.refundAmount()).isEqualByComparingTo("0");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
        verify(productApiClient, org.mockito.Mockito.never()).restorePartial(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyList());
    }

    @Test
    @DisplayName("반품: 신청 → 단순 변심 승인이면 반품 배송비를 뺀 금액을 환불, 주문은 남은 상품이 있어 DELIVERED 유지")
    void returnApprovedWithReturnFee() {
        order("DELIVERED", LocalDateTime.now().minusDays(2));
        given(productApiClient.quoteShipping(anyList())).willReturn(new ShippingQuote(BigDecimal.ZERO, new BigDecimal("2500")));

        ClaimResponse requested = claimService.request(orderId, ME, returning(ClaimReason.CHANGE_OF_MIND, itemB), null);
        assertThat(requested.status()).isEqualTo("REQUESTED");
        assertThat(refundedTotal()).isEqualByComparingTo("0");

        ClaimResponse approved = claimService.approve(requested.id(), null);
        assertThat(approved.returnShippingFee()).isEqualByComparingTo("2500");
        assertThat(approved.refundAmount()).isEqualByComparingTo("17100");
        assertThat(orderStatus()).isEqualTo("DELIVERED");
        // 등급 산정에서 뺄 금액: 품목 20,000 − 할인 몫 400
        assertThat(jdbc.queryForObject("SELECT returned_item_amount FROM orders WHERE id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("관리자가 하자로 사유를 바꿔 승인하면 반품 배송비를 빼지 않는다")
    void approveWithReasonOverride() {
        order("DELIVERED", LocalDateTime.now().minusDays(2));
        given(productApiClient.quoteShipping(anyList())).willReturn(new ShippingQuote(BigDecimal.ZERO, new BigDecimal("2500")));
        ClaimResponse requested = claimService.request(orderId, ME, returning(ClaimReason.CHANGE_OF_MIND, itemB), null);

        ClaimResponse approved = claimService.approve(requested.id(), ClaimReason.DEFECTIVE);

        assertThat(approved.reasonType()).isEqualTo("DEFECTIVE");
        assertThat(approved.refundAmount()).isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("반려하면 잡아 둔 수량을 돌려놓아 다시 신청할 수 있다")
    void rejectReleasesQuantity() {
        order("DELIVERED", LocalDateTime.now().minusDays(2));
        ClaimResponse requested = claimService.request(orderId, ME, returning(ClaimReason.CHANGE_OF_MIND, itemB), null);

        ClaimResponse rejected = claimService.reject(requested.id(), "사용 흔적이 있어 반품할 수 없습니다");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(claimed(itemB)).isZero();
        assertThatThrownBy(() -> claimService.approve(requested.id(), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.claimAlreadyDecided");
    }

    @Test
    @DisplayName("배송 완료 후 7일이 지나면 반품을 받지 않는다")
    void returnWindowClosed() {
        order("DELIVERED", LocalDateTime.now().minusDays(8));

        assertThatThrownBy(() -> claimService.request(orderId, ME, returning(ClaimReason.CHANGE_OF_MIND, itemB), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.returnWindowClosed");
    }

    @Test
    @DisplayName("부분 취소 뒤 관리자 전액 환불은 남은 결제 금액만 돌려준다")
    void adminFullRefundAfterPartialCancel() {
        order("PAID", null);
        claimService.request(orderId, ME, cancel(itemB), null);
        clearInvocations(productApiClient);

        orderService.refundOrder(orderId, new RefundRequest("관리자 전액 환불"));

        assertThat(refundedTotal()).isEqualByComparingTo("49000");
        assertThat(orderStatus()).isEqualTo("REFUNDED");
    }

    // ------------------------------------------------------------------ 교환 (gateway#320)

    private void variantsOfB() {
        given(productApiClient.listVariants(102L)).willReturn(List.of(
                new ProductApiClient.VariantOption(102L, new BigDecimal("20000"), true, 5, "기본"),
                new ProductApiClient.VariantOption(202L, new BigDecimal("20000"), true, 3, "블랙 / L"),
                new ProductApiClient.VariantOption(203L, new BigDecimal("25000"), true, 3, "골드"),
                new ProductApiClient.VariantOption(204L, new BigDecimal("20000"), true, 0, "품절 옵션")));
    }

    private static ClaimCreateRequest exchange(ClaimReason reason, long itemId, long variantId) {
        return new ClaimCreateRequest(ClaimType.EXCHANGE, reason, "색상이 달라요", List.of(new ClaimItemRequest(itemId, 1, variantId)));
    }

    @Test
    @DisplayName("교환: 신청 → 승인(새 옵션 차감·회수품 복원, 환불 없음) → 교환 운송장 등록으로 완료")
    void exchangeFlow() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();

        ClaimResponse requested = claimService.request(orderId, ME, exchange(ClaimReason.WRONG_ITEM, itemB, 202L), null);
        assertThat(requested.status()).isEqualTo("REQUESTED");
        assertThat(requested.items().get(0).exchangeOptionLabel()).isEqualTo("블랙 / L");

        ClaimResponse approved = claimService.approve(requested.id(), null);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.refundAmount()).isEqualByComparingTo("0");
        verify(productApiClient).deductForClaim(orderId, "claim-" + requested.id() + "-out",
                List.of(new ProductApiClient.PartialRestoreItem(202L, 1)));
        verify(productApiClient).restorePartial(orderId, "claim-" + requested.id(),
                List.of(new ProductApiClient.PartialRestoreItem(102L, 1)));
        assertThat(refundedTotal()).isEqualByComparingTo("0");

        ClaimResponse shipped = claimService.shipExchange(requested.id(), "CJ대한통운", "999");
        assertThat(shipped.status()).isEqualTo("COMPLETED");
        assertThat(shipped.exchangeTrackingNumber()).isEqualTo("999");
        assertThat(orderStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("교환은 단순 변심을 받지 않는다 — 실결제 전이라 왕복 배송비를 받을 수 없다")
    void exchangeChangeOfMindRejected() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();

        assertThatThrownBy(() -> claimService.request(orderId, ME, exchange(ClaimReason.CHANGE_OF_MIND, itemB, 202L), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.exchangeReasonNotAllowed");
        assertThat(claimed(itemB)).isZero();
    }

    @Test
    @DisplayName("가격이 다른 옵션·없는 옵션·품절 옵션으로는 교환할 수 없다")
    void exchangeOptionMustMatch() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();

        assertThatThrownBy(() -> claimService.request(orderId, ME, exchange(ClaimReason.DEFECTIVE, itemB, 203L), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.exchangeOptionInvalid");
        assertThatThrownBy(() -> claimService.request(orderId, ME, exchange(ClaimReason.DEFECTIVE, itemB, 999L), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.exchangeOptionInvalid");
        assertThatThrownBy(() -> claimService.request(orderId, ME, exchange(ClaimReason.DEFECTIVE, itemB, 204L), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.exchangeOutOfStock");
    }

    @Test
    @DisplayName("교환 가능 옵션: 같은 가격·판매 중·재고 있는 것만, 같은 옵션 재발송 포함")
    void exchangeOptions() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();
        given(productApiClient.listVariants(101L)).willReturn(List.of());

        var options = claimService.exchangeOptions(orderId, ME).stream()
                .filter(o -> o.orderItemId() == itemB).findFirst().orElseThrow().options();

        assertThat(options).extracting(ClaimDtos.ExchangeOption::variantId).containsExactly(102L, 202L);
    }

    @Test
    @DisplayName("교환한 상품은 남은 상품 금액에 그대로 있다 — 이후 다른 상품 반품의 할인 안분이 교환에 흔들리지 않는다")
    void returnAfterExchangeIgnoresExchange() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();
        ClaimResponse ex = claimService.request(orderId, ME, exchange(ClaimReason.DEFECTIVE, itemB, 202L), null);
        claimService.approve(ex.id(), null);
        claimService.shipExchange(ex.id(), "CJ대한통운", "999");

        ClaimResponse ret = claimService.approve(
                claimService.request(orderId, ME, returning(ClaimReason.DEFECTIVE, itemA), null).id(), null);

        // A 30,000 반품, B(교환됨) 20,000 이 남는다 → 할인 몫 1,000 − ⌊1,000×20,000/50,000⌋ = 600
        assertThat(ret.discountShare()).isEqualByComparingTo("600");
        assertThat(ret.refundAmount()).isEqualByComparingTo("29400");
        assertThat(orderStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("교환 재고가 부족하면 승인되지 않고 신청은 처리 대기로 남는다")
    void exchangeApproveOutOfStock() {
        order("DELIVERED", LocalDateTime.now().minusDays(1));
        variantsOfB();
        ClaimResponse requested = claimService.request(orderId, ME, exchange(ClaimReason.DEFECTIVE, itemB, 202L), null);
        org.mockito.BDDMockito.willThrow(new com.dh.order.service.ExchangeOutOfStockException())
                .given(productApiClient).deductForClaim(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyList());

        assertThatThrownBy(() -> claimService.approve(requested.id(), null))
                .isInstanceOf(OrderStateException.class).hasMessage("order.exchangeOutOfStock");
        assertThat(jdbc.queryForObject("SELECT status FROM order_claims WHERE id = ?", String.class, requested.id()))
                .isEqualTo("REQUESTED");
    }

    // ------------------------------------------------------------------ 관리자 전체 환불 (gateway#329)

    @Test
    @DisplayName("전체 환불을 동시에 두 번 눌러도 환불은 한 건 — 두 번째는 이미 환불된 주문으로 거절된다")
    void concurrentWholeRefundRefundsOnce() throws Exception {
        order("PAID", null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        orderService.refundOrder(orderId, new RefundRequest("고객 요청"));
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var r : results) {
                if (r.get(30, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds", Integer.class)).isEqualTo(1);
        assertThat(refundedTotal()).isEqualByComparingTo("49000");
        assertThat(orderStatus()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("전체 환불 뒤 주문 상세에 환불 금액·사유가 실린다 — 부분 취소로 돌려준 금액은 빠진다")
    void orderDetailCarriesWholeRefund() {
        order("PAID", null);
        assertThat(orderService.getOrder(orderId, Requester.of(null, null, null, true)).refund()).isNull();

        claimService.request(orderId, ME, cancel(itemB), null);
        orderService.refundOrder(orderId, new RefundRequest("품절 안내 후 전체 환불"));

        var refund = orderService.getOrder(orderId, Requester.of(null, null, null, true)).refund();
        assertThat(refund).isNotNull();
        assertThat(refund.reason()).isEqualTo("품절 안내 후 전체 환불");
        assertThat(refund.amount().add(refundedTotal().subtract(refund.amount())))
                .isEqualByComparingTo(refundedTotal());
        assertThat(refundedTotal()).isEqualByComparingTo("49000");
    }
}
