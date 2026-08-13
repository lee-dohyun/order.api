-- =========================================================
-- 베이스라인: Flyway 도입 이전(ddl-auto:update)에 이미 존재하던 스키마를 그대로 기록.
-- 운영 DB는 baseline-on-migrate로 이 버전을 "이미 적용됨"으로 표시하고 건너뛰므로,
-- 이 파일은 신규/로컬 DB를 처음부터 만들 때만 실제로 실행된다.
-- =========================================================
CREATE TABLE orders (
    id                BIGSERIAL PRIMARY KEY,
    created_at        TIMESTAMP(6) NOT NULL,
    orderer_name      VARCHAR(100) NOT NULL,
    orderer_phone     VARCHAR(30) NOT NULL,
    shipping_address  VARCHAR(300) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    total_price       NUMERIC(12,2) NOT NULL,
    paid_at           TIMESTAMP(6),
    customer_email    VARCHAR(320),
    address1          VARCHAR(200),
    address2          VARCHAR(200),
    recipient_name    VARCHAR(50),
    recipient_phone   VARCHAR(30),
    zip_code          VARCHAR(10),
    CONSTRAINT orders_status_check CHECK (status IN ('CREATED', 'PAID'))
);

CREATE TABLE order_items (
    id            BIGSERIAL PRIMARY KEY,
    price         NUMERIC(12,2) NOT NULL,
    product_id    BIGINT NOT NULL,
    product_name  VARCHAR(200) NOT NULL,
    quantity      INT NOT NULL,
    order_id      BIGINT NOT NULL REFERENCES orders(id),
    variant_id    BIGINT NOT NULL
);

CREATE TABLE shipments (
    id               BIGSERIAL PRIMARY KEY,
    carrier          VARCHAR(50) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    delivered_at     TIMESTAMP(6),
    shipped_at       TIMESTAMP(6),
    status           VARCHAR(20) NOT NULL,
    tracking_number  VARCHAR(50) NOT NULL,
    order_id         BIGINT NOT NULL UNIQUE REFERENCES orders(id),
    CONSTRAINT shipments_status_check CHECK (status IN ('PREPARING', 'SHIPPED', 'DELIVERED'))
);
