-- 주문 생성 멱등 키 (gateway#306)
--
-- 배포 직후 첫 POST /api/orders 가 게이트웨이에서 타임아웃(503)으로 끝났는데 order.api 는 처리를 마쳐
-- 주문 행을 만들었다(2026-10-02 운영 실측). 고객은 실패를 보고 다시 주문해 결제 안 된 CREATED 주문이
-- 하나 더 남는다. 캐논("모든 쓰기 API 는 멱등")대로 클라이언트가 보낸 Idempotency-Key 로 재시도를
-- 같은 주문에 묶는다.
--
--   idempotency_key           = 클라이언트가 주문 시도마다 만든 값(UUID). 키 없이 온 요청과 이 컬럼
--                               이전 주문은 NULL.
--   idempotency_request_hash  = 그 키로 처음 받은 요청 내용의 SHA-256(hex). 같은 키에 다른 내용이
--                               오면 예전 주문을 새 주문인 것처럼 돌려주지 않고 거부하기 위한 값이다.
--
-- 최종 방어는 아래 유니크 인덱스다. 애플리케이션의 "먼저 조회"는 동시에 들어온 두 요청이 둘 다
-- "없음"을 볼 수 있어 충분하지 않다(payments.order_id UNIQUE 와 같은 구조).
-- NULL 은 여러 개 허용돼야 하므로 부분 인덱스로 건다.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64) NULL;
ALTER TABLE orders ADD COLUMN idempotency_request_hash VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_orders_idempotency_key ON orders (idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN orders.idempotency_key IS
    '주문 생성 멱등 키(Idempotency-Key 헤더). 같은 키의 재시도는 이 주문을 돌려받는다. 키 없는 요청은 NULL.';
COMMENT ON COLUMN orders.idempotency_request_hash IS
    '멱등 키로 처음 받은 요청 내용의 SHA-256(hex). 같은 키·다른 내용 요청을 거부하는 데 쓴다.';
