package com.dh.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import com.dh.order.config.AuthApiClient;
import com.dh.order.config.ProductApiClient;
import com.dh.order.config.ProductApiClient.ResolvedOffer;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderItemRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.Requester;

/**
 * 주문 생성의 멱등 키(gateway#306)를 실제 Postgres 로 실측한다. "같은 키로 두 번 → 주문 한 건"의 최종
 * 방어는 {@code orders.idempotency_key} 유니크 인덱스라, 목 리포지토리로는 증명할 수 없다(캐논: 멱등성
 * 변경은 DB 상태 실측으로 검증한다). product.api·auth.api 만 목이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderIdempotencyIntegrationTest {

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
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Long SKU = 42L;
    /** 클라이언트가 주문 시도마다 만드는 값과 같은 모양(UUID). 고정 문자열로 두면 시크릿 스캔이 키로 오인한다. */
    private static final String KEY = UUID.randomUUID().toString();
    private static final String 회원_SUB = "2f1c7a3e-0000-4000-8000-000000000001";

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        given(productApiClient.resolveOffers(anyList())).willReturn(Map.of());
        given(productApiClient.resolveFeaturedOffersByVariant(anyList())).willReturn(Map.of(SKU, 오퍼()));
        given(authApiClient.findMemberGrade(회원_SUB)).willReturn(Optional.empty());
    }

    private static ResolvedOffer 오퍼() {
        return new ResolvedOffer(501L, SKU, 7L, "상품", 1L, "포스셀렉트", new BigDecimal("10000"),
                BigDecimal.ZERO, true, null, true, null);
    }

    private static OrderCreateRequest 주문요청(int quantity) {
        return new OrderCreateRequest("홍길동", "010-1234-5678", "서울시 강남구 테헤란로 1",
                null, null, null, null, null, List.of(new OrderItemRequest(null, SKU, quantity)));
    }

    private static Requester 게스트() {
        return Requester.of(null, null, null, false);
    }

    private static Requester 회원() {
        return Requester.of(회원_SUB, "member@example.com", null, false);
    }

    private int 주문_수() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    @Test
    @DisplayName("같은 키로 두 번 주문하면 주문은 한 건이고 같은 주문을 돌려받는다")
    void sameKeyTwice_CreatesOneOrder() {
        OrderResponse first = orderService.createOrder(1L, 주문요청(2), 회원(), KEY);
        OrderResponse second = orderService.createOrder(1L, 주문요청(2), 회원(), KEY);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.totalPrice()).isEqualByComparingTo(first.totalPrice());
        assertThat(second.items()).hasSize(1);
        assertThat(주문_수()).isEqualTo(1);
        // 재시도는 가격 확정(원격 호출)까지 가지 않는다 — 처음 확정한 주문을 그대로 돌려준다.
        verify(productApiClient, times(1)).resolveFeaturedOffersByVariant(anyList());
    }

    @Test
    @DisplayName("게스트의 재시도는 처음 응답과 같은 게스트 토큰을 다시 받는다")
    void guestRetry_GetsSameGuestToken() {
        OrderResponse first = orderService.createOrder(1L, 주문요청(1), 게스트(), KEY);
        OrderResponse second = orderService.createOrder(1L, 주문요청(1), 게스트(), KEY);

        // 첫 응답을 못 받은 게스트(게이트웨이 타임아웃)는 이 토큰이 없으면 결제도 조회도 못 한다.
        assertThat(first.guestToken()).isNotBlank();
        assertThat(second.guestToken()).isEqualTo(first.guestToken());
        assertThat(주문_수()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키에 다른 주문 내용을 보내면 거부한다 — 예전 주문을 새 주문인 것처럼 돌려주지 않는다")
    void sameKeyDifferentBody_IsRejected() {
        orderService.createOrder(1L, 주문요청(1), 회원(), KEY);

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(3), 회원(), KEY))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.idempotencyKeyReused");
        assertThat(주문_수()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 키로는 그 주문을 받아 가지 못한다")
    void sameKeyDifferentRequester_IsRejected() {
        orderService.createOrder(1L, 주문요청(1), 회원(), KEY);

        assertThatThrownBy(() -> orderService.createOrder(1L, 주문요청(1), 게스트(), KEY))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.idempotencyKeyReused");
        assertThat(주문_수()).isEqualTo(1);
    }

    @Test
    @DisplayName("키가 없으면 예전처럼 요청마다 주문을 만든다")
    void noKey_CreatesOrderPerRequest() {
        OrderResponse first = orderService.createOrder(1L, 주문요청(1), 회원(), null);
        OrderResponse second = orderService.createOrder(1L, 주문요청(1), 회원(), null);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(주문_수()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 키의 요청이 동시에 들어와도 주문은 한 건이다")
    void concurrentSameKey_CreatesOneOrder() throws Exception {
        // 두 요청이 모두 "아직 그 키의 주문이 없다"를 확인한 뒤에야 가격 확정을 지나가게 한다.
        // 이 상태에서 둘 다 저장을 시도하므로, 한 건으로 남기는 것은 유니크 인덱스뿐이다.
        CountDownLatch bothPastLookup = new CountDownLatch(2);
        given(productApiClient.resolveFeaturedOffersByVariant(anyList())).willAnswer(inv -> {
            bothPastLookup.countDown();
            bothPastLookup.await(10, TimeUnit.SECONDS);
            return Map.of(SKU, 오퍼());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrderResponse> a = pool.submit(() -> orderService.createOrder(1L, 주문요청(1), 회원(), KEY));
            Future<OrderResponse> b = pool.submit(() -> orderService.createOrder(1L, 주문요청(1), 회원(), KEY));

            assertThat(a.get(30, TimeUnit.SECONDS).id()).isEqualTo(b.get(30, TimeUnit.SECONDS).id());
        } finally {
            pool.shutdownNow();
        }
        assertThat(주문_수()).isEqualTo(1);
        verify(productApiClient, times(2)).resolveFeaturedOffersByVariant(anyList());
    }
}
