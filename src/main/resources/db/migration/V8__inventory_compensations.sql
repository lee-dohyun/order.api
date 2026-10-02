-- 재고 차감·복원의 미결 기록(order.api#34). product.api 의 재고와 이 서비스의 주문 상태는 서로 다른
-- DB 라 한 트랜잭션으로 묶을 수 없다. 그래서 "재고에 아직 정리되지 않은 일이 있는 주문"을 이 테이블에
-- 남기고, 스케줄러가 끝까지 정리한다. 이전에는 복원 호출이 실패하면 로그 한 줄뿐이었고, 차감과 결제
-- 확정 사이에 파드가 죽으면 아무 기록도 남지 않았다.
--
-- kind
--   PAYMENT : 결제 시도. 차감 요청을 보내기 "전"에 WATCHING 으로 남긴다(주문당 1행, 재결제 때 재사용).
--   REFUND  : 환불. REFUNDED 전이와 같은 트랜잭션에서 RESTORING 으로 남긴다.
-- status
--   WATCHING  : 차감이 나갔을 수 있고 결제 결과는 아직 모른다. PAID 커밋과 같은 트랜잭션에서 DONE 이 된다.
--               오래 남아 있으면 그 시도는 중간에 죽은 것이다.
--   RESTORING : 재고를 되돌려야 한다. 성공할 때까지 재시도한다. 이 상태인 주문은 결제를 받지 않는다.
--   DONE      : 정리 끝.
CREATE TABLE inventory_compensations (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES orders(id),
    kind          VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    attempts      INTEGER NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    -- 마지막으로 이 행을 건드린 시각. WATCHING 의 방치 판정과 RESTORING 의 재시도 간격이 모두 이 값을 본다.
    touched_at    TIMESTAMP(6) NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    completed_at  TIMESTAMP(6),
    CONSTRAINT inventory_compensations_kind_check CHECK (kind IN ('PAYMENT', 'REFUND')),
    CONSTRAINT inventory_compensations_status_check CHECK (status IN ('WATCHING', 'RESTORING', 'DONE')),
    CONSTRAINT uq_inventory_compensations_order_kind UNIQUE (order_id, kind)
);

-- 스케줄러는 미결 행만 본다. DONE 은 계속 쌓이므로 인덱스에서 뺀다.
CREATE INDEX idx_inventory_compensations_open
    ON inventory_compensations (status, touched_at)
    WHERE status <> 'DONE';
