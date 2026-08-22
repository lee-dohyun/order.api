package com.dh.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.repository.OrderRepository.PurchaseSummaryRow;

/**
 * 회원 등급 산정의 근거가 되는 구매확정액 집계 (gateway#86 등급 시스템).
 *
 * <p>등급은 <b>금전적 혜택</b>이라 집계 규칙이 틀리면 그대로 손실이거나 불공정이 된다.
 * 특히 "환불한 주문이 등급에 남는가"는 반복 주문·환불로 등급을 올릴 수 있느냐의 문제라
 * 스키마가 아니라 <b>쿼리 자체</b>를 고정해 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PurchaseSummaryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDateTime SINCE = LocalDateTime.now().minusMonths(6);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
    }

    private void insertOrder(String customerId, String status, long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO orders (status, total_price, created_at, channel_id, customer_id, "
                        + "orderer_name, orderer_phone, shipping_address, recipient_name, recipient_phone) "
                        + "VALUES (?, ?, ?, 1, ?, '주문자', '01000000000', '주소', '수령인', '01000000000')",
                status, amount, createdAt, customerId);
    }

    private Map<String, BigDecimal> summarize() {
        List<PurchaseSummaryRow> rows = orderRepository.sumConfirmedPurchasesSince(SINCE);
        return rows.stream().collect(Collectors.toMap(
                PurchaseSummaryRow::getCustomerId, PurchaseSummaryRow::getConfirmedAmount, (a, b) -> a));
    }

    @Test
    @DisplayName("DELIVERED 주문만 합산된다")
    void onlyDeliveredCounts() {
        insertOrder("user-1", "DELIVERED", 10_000, LocalDateTime.now());
        insertOrder("user-1", "PAID", 50_000, LocalDateTime.now());
        insertOrder("user-1", "SHIPPED", 50_000, LocalDateTime.now());
        insertOrder("user-1", "PREPARING", 50_000, LocalDateTime.now());

        assertThat(summarize().get("user-1")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("환불·취소 주문은 제외된다 — 주문 후 환불을 반복해 등급을 올릴 수 없어야 한다")
    void refundedAndCancelledAreExcluded() {
        insertOrder("user-1", "DELIVERED", 10_000, LocalDateTime.now());
        insertOrder("user-1", "REFUNDED", 900_000, LocalDateTime.now());
        insertOrder("user-1", "CANCELLED", 900_000, LocalDateTime.now());

        assertThat(summarize().get("user-1")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("기준 기간 이전 주문은 제외된다")
    void ordersBeforeWindowAreExcluded() {
        insertOrder("user-1", "DELIVERED", 10_000, LocalDateTime.now());
        insertOrder("user-1", "DELIVERED", 900_000, LocalDateTime.now().minusMonths(7));

        assertThat(summarize().get("user-1")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("게스트 주문(customer_id null)은 집계에 잡히지 않는다")
    void guestOrdersAreExcluded() {
        insertOrder(null, "DELIVERED", 900_000, LocalDateTime.now());
        insertOrder("user-1", "DELIVERED", 10_000, LocalDateTime.now());

        Map<String, BigDecimal> result = summarize();
        assertThat(result).containsOnlyKeys("user-1");
    }

    @Test
    @DisplayName("회원별로 나뉘어 합산된다")
    void groupedPerCustomer() {
        insertOrder("user-1", "DELIVERED", 10_000, LocalDateTime.now());
        insertOrder("user-1", "DELIVERED", 20_000, LocalDateTime.now());
        insertOrder("user-2", "DELIVERED", 30_000, LocalDateTime.now());

        Map<String, BigDecimal> result = summarize();
        assertThat(result.get("user-1")).isEqualByComparingTo("30000");
        assertThat(result.get("user-2")).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("구매확정이 없는 회원은 결과에 아예 없다 — 호출부가 0원으로 처리해야 한다")
    void membersWithoutConfirmedPurchasesAreAbsent() {
        insertOrder("user-1", "PAID", 900_000, LocalDateTime.now());

        assertThat(summarize()).isEmpty();
    }
}
