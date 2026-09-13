package com.dh.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V7 마이그레이션 검증 (order.api#14, gateway#212 3단계).
 *
 * <p>V6 테스트({@link OrderItemSellerSnapshotIntegrationTest})와 같은 이유로 스키마를 직접 본다 —
 * 엔티티 어노테이션과 DB 가 실제로 받아들이는 것은 다른 이야기다. 특히 {@code seller_name} 확장은
 * {@code ddl-auto: validate} 가 varchar 길이 차이를 잡지 않아서, 엔티티만 200 으로 바꾸고 SQL 을
 * 빠뜨려도 부팅은 성공한다. 그 누락은 긴 상호의 주문이 들어오는 순간에야 INSERT 실패로 드러난다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderItemOfferIdMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("offer_id 가 NULL 허용으로 존재한다 — 이 컬럼 이전 주문은 오퍼 개념 없이 확정됐으므로 비워 둔다")
    void offerIdExistsAndIsNullable() {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'order_items' AND column_name = 'offer_id'",
                String.class);

        assertThat(nullable).isEqualTo("YES");
    }

    @Test
    @DisplayName("오퍼별 조회 인덱스가 있다")
    void offerIdIndexExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE tablename = 'order_items' AND indexname = 'idx_order_items_offer_id'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("seller_name 최대 길이가 200 이다 — 판매자 상호가 sellers.name(VARCHAR 200)에서 온다")
    void sellerNameWidenedTo200() {
        Integer maxLength = jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'order_items' AND column_name = 'seller_name'",
                Integer.class);

        assertThat(maxLength).isEqualTo(200);
    }

    @Test
    @DisplayName("100자를 넘는 상호도 실제로 저장된다 — 메타데이터가 아니라 DB 가 받아들이는지 본다")
    void longSellerNameIsAccepted() {
        Long orderId = insertOrder();
        String longName = "가".repeat(150);

        assertThatCode(() -> jdbcTemplate.update(
                "INSERT INTO order_items "
                        + "(order_id, product_id, variant_id, product_name, price, quantity, seller_id, seller_name, offer_id) "
                        + "VALUES (?, 1, 1, '상품', 1000, 1, 7, ?, 501)",
                orderId, longName))
                .doesNotThrowAnyException();

        String stored = jdbcTemplate.queryForObject(
                "SELECT seller_name FROM order_items WHERE order_id = ?", String.class, orderId);
        assertThat(stored).hasSize(150);
    }

    @Test
    @DisplayName("offer_id 없이 넣는 기존 형태의 INSERT 도 그대로 된다 — 전환 전 코드·배치가 깨지지 않는다")
    void insertWithoutOfferIdStillWorks() {
        Long orderId = insertOrder();

        jdbcTemplate.update(
                "INSERT INTO order_items "
                        + "(order_id, product_id, variant_id, product_name, price, quantity, seller_name) "
                        + "VALUES (?, 1, 1, '상품', 1000, 1, '포스셀렉트')",
                orderId);

        Long offerId = jdbcTemplate.queryForObject(
                "SELECT offer_id FROM order_items WHERE order_id = ?", Long.class, orderId);
        assertThat(offerId).isNull();
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
