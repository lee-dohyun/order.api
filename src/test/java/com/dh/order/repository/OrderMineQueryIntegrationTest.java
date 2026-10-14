package com.dh.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.domain.Order;

/**
 * 내 주문 목록의 기간·페이징(gateway#321). 소유자 조건은 기존 {@code findMine} 과 같아야 한다 — 남의 주문이
 * 섞이면 그대로 개인정보 노출이다. JPQL 의 날짜 비교·정렬·페이징은 실제 Postgres 에서만 의미가 있어
 * 통합 테스트로 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderMineQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        insert("me", null, BASE.minusDays(40));
        insert("me", null, BASE.minusDays(10));
        insert("me", null, BASE.minusDays(5));
        insert("me", null, BASE.minusDays(1));
        insert("other", null, BASE.minusDays(5));
        // 회원 키가 생기기 전 이메일로만 남은 옛 주문 — 기존 findMine 과 같은 규칙으로 포함된다.
        insert(null, "me@example.com", BASE.minusDays(3));
    }

    @Test
    @DisplayName("기간 안의 내 주문만, 최신순으로 — 남의 주문은 섞이지 않는다")
    void filtersByPeriodAndOwner() {
        var result = orderRepository.findMineBetween("me", "me@example.com",
                BASE.minusDays(11), BASE, Pageable.unpaged());

        assertThat(result).extracting(Order::getCreatedAt).containsExactly(
                BASE.minusDays(1), BASE.minusDays(3), BASE.minusDays(5), BASE.minusDays(10));
        assertThat(result).noneMatch(o -> "other".equals(o.getCustomerId()));
    }

    @Test
    @DisplayName("끝 시각은 포함하지 않는다(반열린 구간) — 하루 단위로 이어 붙여도 겹치지 않는다")
    void upperBoundIsExclusive() {
        var result = orderRepository.findMineBetween("me", null,
                BASE.minusDays(10), BASE.minusDays(5), Pageable.unpaged());

        assertThat(result).extracting(Order::getCreatedAt).containsExactly(BASE.minusDays(10));
    }

    @Test
    @DisplayName("페이지 단위로 자른다")
    void pages() {
        var first = orderRepository.findMineBetween("me", "me@example.com",
                BASE.minusYears(1), BASE, PageRequest.of(0, 2));
        var second = orderRepository.findMineBetween("me", "me@example.com",
                BASE.minusYears(1), BASE, PageRequest.of(1, 2));

        assertThat(first).extracting(Order::getCreatedAt).containsExactly(BASE.minusDays(1), BASE.minusDays(3));
        assertThat(second).extracting(Order::getCreatedAt).containsExactly(BASE.minusDays(5), BASE.minusDays(10));
    }

    private void insert(String customerId, String email, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO orders (status, total_price, created_at, channel_id, customer_id, customer_email, "
                        + "orderer_name, orderer_phone, shipping_address, recipient_name, recipient_phone) "
                        + "VALUES ('PAID', 1000, ?, 1, ?, ?, '주문자', '01000000000', '주소', '수령인', '01000000000')",
                createdAt, customerId, email);
    }
}
