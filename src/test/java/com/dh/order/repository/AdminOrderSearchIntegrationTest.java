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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.domain.Order;
import com.dh.order.domain.OrderStatus;

/**
 * 관리자 주문 검색(gateway#329). Criteria 의 replace()·like·null 컬럼 처리와 페이징 건수는 실제 Postgres 에서만
 * 의미가 있어 통합 테스트로 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AdminOrderSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final PageRequest ALL = PageRequest.of(0, 50, Sort.by(Sort.Order.desc("createdAt")));

    private long kimId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        kimId = insert("PAID", "김철수", "010-1234-5678", "kim@example.com", null, BASE.minusDays(1));
        insert("SHIPPED", "이영희", "01098765432", null, "guest_lee@example.com", BASE.minusDays(2));
        insert("REFUNDED", "박민수", "010-5555-0000", "park@example.com", null, BASE.minusDays(10));
        insert("PAID", "100%_할인", "010-0000-1111", "promo@example.com", null, BASE.minusDays(3));
    }

    @Test
    @DisplayName("조건이 없으면 전부, 최신순")
    void noFilter() {
        var page = orderRepository.findAll(new AdminOrderSearch(null, null, null, null).toSpecification(), ALL);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(Order::getOrdererName)
                .containsExactly("김철수", "이영희", "100%_할인", "박민수");
    }

    @Test
    @DisplayName("상태와 기간(하한 포함·상한 제외)으로 거른다")
    void statusAndPeriod() {
        assertThat(names(new AdminOrderSearch(OrderStatus.PAID, null, null, null)))
                .containsExactly("김철수", "100%_할인");
        assertThat(names(new AdminOrderSearch(null, BASE.minusDays(3), BASE.minusDays(1), null)))
                .containsExactly("이영희", "100%_할인");
    }

    @Test
    @DisplayName("연락처는 하이픈 유무와 상관없이 숫자로 찾는다")
    void phoneDigits() {
        assertThat(names(new AdminOrderSearch(null, null, null, "1234-5678"))).containsExactly("김철수");
        assertThat(names(new AdminOrderSearch(null, null, null, "9876-5432"))).containsExactly("이영희");
    }

    @Test
    @DisplayName("이름·회원 이메일·비회원 연락 이메일을 대소문자 없이 부분 일치로 찾는다")
    void nameAndEmails() {
        assertThat(names(new AdminOrderSearch(null, null, null, "철수"))).containsExactly("김철수");
        assertThat(names(new AdminOrderSearch(null, null, null, "PARK@"))).containsExactly("박민수");
        assertThat(names(new AdminOrderSearch(null, null, null, "guest_lee"))).containsExactly("이영희");
    }

    @Test
    @DisplayName("주문번호는 정확히 일치한다(# 를 붙여도 된다)")
    void orderId() {
        assertThat(names(new AdminOrderSearch(null, null, null, "#" + kimId))).contains("김철수");
        assertThat(orderRepository.findAll(new AdminOrderSearch(null, null, null, String.valueOf(kimId))
                .toSpecification(), ALL).getContent()).extracting(Order::getId).contains(kimId);
    }

    @Test
    @DisplayName("% 와 _ 는 와일드카드가 아니라 글자로 찾는다")
    void likeWildcardsAreEscaped() {
        assertThat(names(new AdminOrderSearch(null, null, null, "0%_"))).containsExactly("100%_할인");
        assertThat(names(new AdminOrderSearch(null, null, null, "_"))).containsExactly("이영희", "100%_할인");
    }

    @Test
    @DisplayName("페이지 단위로 자르고 전체 건수를 준다")
    void pages() {
        var spec = new AdminOrderSearch(null, null, null, null).toSpecification();
        var first = orderRepository.findAll(spec, PageRequest.of(0, 3, Sort.by(Sort.Order.desc("createdAt"))));
        var second = orderRepository.findAll(spec, PageRequest.of(1, 3, Sort.by(Sort.Order.desc("createdAt"))));

        assertThat(first.getContent()).hasSize(3);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(Order::getOrdererName).containsExactly("박민수");
    }

    private java.util.List<String> names(AdminOrderSearch search) {
        return orderRepository.findAll(search.toSpecification(), ALL).getContent().stream()
                .map(Order::getOrdererName).toList();
    }

    private long insert(String status, String name, String phone, String email, String guestEmail,
            LocalDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO orders (status, total_price, created_at, channel_id, customer_email, guest_email, "
                        + "orderer_name, orderer_phone, shipping_address, recipient_name, recipient_phone) "
                        + "VALUES (?, 1000, ?, 1, ?, ?, ?, ?, '주소', '수령인', '01000000000') RETURNING id",
                Long.class, status, createdAt, email, guestEmail, name, phone);
    }
}
