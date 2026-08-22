package com.dh.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V6 판매자 스냅샷 마이그레이션 검증 (gateway#212 0단계, order.api#13).
 *
 * <p><b>왜 스키마를 직접 보는가.</b> 이 컬럼의 존재 이유는 "판매자 미상 행이 생길 수 없게 한다"는
 * 것 하나다. 소급이 불가능하기 때문에 <b>NOT NULL 이 실제로 걸려 있는지</b>가 곧 이 작업의 성패다.
 * 엔티티에 어노테이션이 붙어 있는 것과 DB 가 거절하는 것은 다른 이야기이고, 애플리케이션을 우회하는
 * 경로(배치, 수동 INSERT, 다른 서비스)는 후자만 막을 수 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderItemSellerSnapshotIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String nullability(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'order_items' AND column_name = ?",
                String.class, column);
    }

    @Test
    @DisplayName("seller_id / seller_name 이 NOT NULL 로 존재한다")
    void columnsExistAndAreNotNull() {
        assertThat(nullability("seller_id")).isEqualTo("NO");
        assertThat(nullability("seller_name")).isEqualTo("NO");
    }

    @Test
    @DisplayName("seller_id 기본값 1 — 애플리케이션이 값을 안 채워도 자사 판매로 기록된다")
    void sellerIdDefaultsToFirstParty() {
        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'order_items' AND column_name = 'seller_id'",
                String.class);

        assertThat(columnDefault).contains("1");
    }

    @Test
    @DisplayName("판매자별 조회 인덱스가 있다 — 3P 정산·CS 에서 첫 번째로 필요해진다")
    void sellerIndexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'order_items' AND indexname = 'idx_order_items_seller_id'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("seller_name 을 NULL 로 넣으면 DB 가 거절한다 — 애플리케이션을 우회해도 판매자 미상 행은 못 만든다")
    void nullSellerNameIsRejectedByDatabase() {
        Long orderId = insertOrder();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO order_items "
                        + "(order_id, product_id, variant_id, product_name, price, quantity, seller_name) "
                        + "VALUES (?, 1, 1, '상품', 1000, 1, NULL)",
                orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("seller_name 만 주면 seller_id 는 기본값으로 채워진다")
    void sellerIdIsBackfilledByDefault() {
        Long orderId = insertOrder();

        jdbcTemplate.update(
                "INSERT INTO order_items "
                        + "(order_id, product_id, variant_id, product_name, price, quantity, seller_name) "
                        + "VALUES (?, 1, 1, '상품', 1000, 1, '포스셀렉트')",
                orderId);

        Long sellerId = jdbcTemplate.queryForObject(
                "SELECT seller_id FROM order_items WHERE order_id = ?", Long.class, orderId);
        assertThat(sellerId).isEqualTo(1L);
    }

    /** V5 가 시드한 기본 채널(id=1)을 그대로 쓴다. */
    private Long insertOrder() {
        jdbcTemplate.update(
                "INSERT INTO orders (status, total_price, created_at, channel_id, "
                        + "orderer_name, orderer_phone, shipping_address, recipient_name, recipient_phone) "
                        + "VALUES ('CREATED', 1000, now(), 1, '주문자', '01000000000', '주소', '수령인', '01000000000')");
        return jdbcTemplate.queryForObject("SELECT max(id) FROM orders", Long.class);
    }
}
