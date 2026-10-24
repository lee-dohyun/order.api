-- 고객 메일 발송 실패 기록(order.api#44). 메일은 출고·환불을 되돌릴 수 없는 부수효과라 실패해도
-- 본 작업은 성공으로 두는데, 그때 로그 한 줄만 남으면 "누구에게 안 갔는지"를 나중에 알 수 없다.
-- 이 테이블은 재시도 큐가 아니라 미발송 기록이다 — 운영자가 보고 직접 안내하거나 재발송 여부를 판단한다.
-- 수신자 주소는 넣지 않는다(orders 에 이미 있고, 여기까지 복제할 이유가 없다).
CREATE TABLE notification_failures (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id),
    kind        VARCHAR(30) NOT NULL,
    last_error  VARCHAR(500),
    created_at  TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_notification_failures_order ON notification_failures (order_id);
