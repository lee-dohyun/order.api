package com.dh.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dh.order.domain.Channel;
import com.dh.order.domain.Order;
import com.dh.order.domain.OrderStatus;

/**
 * Flyway 마이그레이션이 실제 Postgres에 정확히 적용되는지, 그리고 {@code orders_status_check} CHECK
 * 제약이 애플리케이션 로직과 별개로 동작하는지 검증하는 통합 테스트 (posselect-shell#26, architecture#14).
 *
 * <p><b>왜 단위 테스트가 아니라 이건가.</b> 이 저장소의 기존 6개 테스트는 전부 Mockito 목 리포지토리라
 * DB에 한 번도 닿지 않는다. {@code OrderStatus}는 CREATED/PAID 2개로 시작해 7개로 늘었는데(#99),
 * V1 베이스라인의 {@code orders_status_check}는 CREATED/PAID만 허용하고 V2가 이를 7개로 넓힌다.
 * 목 테스트는 이 CHECK 제약 자체를 절대 실행하지 않으므로, V2가 깨지거나 순서가 바뀌어도 초록불이
 * 그대로 유지된다 — 이게 order.api CLAUDE.md가 "테스트가 무엇을 증명하고 무엇을 못 하는가"에서
 * 명시한 공백이다. 이 테스트는 컨테이너에 V1~V5를 실제로 태운 뒤, 서비스 계층을 거치지 않고
 * 리포지토리로 직접 저장/조회해 그 공백을 메운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderStatusMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Channel channel;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        // V5가 기본 채널(id=1, domain=posselect.com)을 시드한다 - 새로 만들지 않고 그대로 재사용한다.
        channel = channelRepository.findById(1L).orElseThrow();
    }

    private Order newOrder(OrderStatus status) {
        Order order = new Order();
        order.setChannel(channel);
        order.setOrdererName("홍길동");
        order.setOrdererPhone("010-1234-5678");
        order.setShippingAddress("서울시 강남구 테헤란로 1");
        order.setTotalPrice(new BigDecimal("10000.00"));
        order.setStatus(status);
        return order;
    }

    @ParameterizedTest(name = "{0} 상태 저장이 성공한다 - V2가 CHECK 제약을 실제로 넓혔는지 검증")
    @EnumSource(OrderStatus.class)
    @DisplayName("OrderStatus 7개 전부 실제 Postgres CHECK 제약을 통과한다")
    void 확장된_상태값_전부_저장된다(OrderStatus status) {
        Order saved = orderRepository.saveAndFlush(newOrder(status));

        String committedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, saved.getId());

        assertThat(committedStatus).isEqualTo(status.name());
    }

    @Test
    @DisplayName("CHECK 제약이 정의되지 않은 값은 애플리케이션을 거치지 않아도 DB가 거부한다")
    void 정의되지_않은_상태값은_DB가_거부한다() {
        // OrderStatus enum에는 없는 값을 리포지토리를 우회해 직접 넣어본다 - 응용 로직이 실수로
        // enum 검증을 빠뜨려도 이 CHECK 제약이 최후의 방어선이어야 한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO orders "
                        + "(channel_id, created_at, orderer_name, orderer_phone, shipping_address, "
                        + "status, total_price) VALUES (?, ?, ?, ?, ?, 'BOGUS_STATUS', ?)",
                channel.getId(), LocalDateTime.now(), "홍길동", "010-1234-5678", "서울시 강남구", new BigDecimal("10000.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE status = 'BOGUS_STATUS'", Integer.class))
                .isZero();
    }
}
