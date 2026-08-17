-- #100 결정(order.api 내 확장)에 따른 실제 테이블 구현. 결제는 order 1:1(재시도/부분결제 없음,
-- 아직 실 PG 연동 전이라 mock 결제 성공 시점에 1건만 생성), 환불은 결제 1:N(추후 부분환불 대비).
CREATE TABLE payments (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL UNIQUE REFERENCES orders(id),
    amount      NUMERIC(12,2) NOT NULL,
    method      VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    paid_at     TIMESTAMP(6) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT payments_status_check CHECK (status IN ('COMPLETED', 'FAILED'))
);

CREATE TABLE refunds (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   BIGINT NOT NULL REFERENCES payments(id),
    amount       NUMERIC(12,2) NOT NULL,
    reason       VARCHAR(300),
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    refunded_at  TIMESTAMP(6),
    CONSTRAINT refunds_status_check CHECK (status IN ('REQUESTED', 'COMPLETED', 'REJECTED'))
);
