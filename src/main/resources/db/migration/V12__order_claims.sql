-- =========================================================
-- 고객 상품별 취소·반품 (gateway#320, 2026-10-14 결정)
--
-- order_claims       : 취소·반품 한 건. 출고 전 취소는 즉시 COMPLETED, 반품은 REQUESTED → 관리자 승인(COMPLETED)
--                      또는 반려(REJECTED). 교환(EXCHANGE)은 다음 단계라 아직 값에 없다.
-- order_claim_items  : 클레임이 돌려받는 주문 품목과 수량.
-- order_items.claimed_quantity : 진행 중(REQUESTED)·완료(COMPLETED) 클레임이 잡고 있는 수량. 반려되면 돌려놓는다.
--                      주문 수량을 넘지 못하게 DB 가 막는다 — 같은 품목을 동시에 두 번 신청해도 초과하지 않는다.
-- 금액 칼럼은 완료 시점에 채우는 스냅샷이다(계산 규칙은 ClaimRefundCalculator). 다음 클레임의 할인 안분·배송비
-- 재계산이 앞선 클레임의 몫을 읽어야 해서 남겨 둔다.
-- =========================================================
CREATE TABLE order_claims (
    id                   BIGSERIAL PRIMARY KEY,
    order_id             BIGINT NOT NULL REFERENCES orders(id),
    type                 VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    reason_type          VARCHAR(30) NOT NULL,
    reason_detail        VARCHAR(500),
    -- 고객 재시도 dedup. 같은 키의 재요청은 새 클레임을 만들지 않고 처음 것을 돌려준다.
    idempotency_key      VARCHAR(100),
    item_amount          NUMERIC(12,2),
    discount_share       NUMERIC(12,2),
    shipping_refund      NUMERIC(12,2),
    return_shipping_fee  NUMERIC(12,2),
    refund_amount        NUMERIC(12,2),
    reject_reason        VARCHAR(300),
    created_at           TIMESTAMP(6) NOT NULL,
    decided_at           TIMESTAMP(6),
    CONSTRAINT order_claims_type_check CHECK (type IN ('CANCEL', 'RETURN')),
    CONSTRAINT order_claims_status_check CHECK (status IN ('REQUESTED', 'COMPLETED', 'REJECTED')),
    CONSTRAINT order_claims_reason_check CHECK (reason_type IN ('CHANGE_OF_MIND', 'DEFECTIVE', 'WRONG_ITEM')),
    CONSTRAINT order_claims_refund_non_negative CHECK (refund_amount IS NULL OR refund_amount >= 0)
);
CREATE UNIQUE INDEX uq_order_claims_idempotency_key ON order_claims (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_order_claims_order ON order_claims (order_id);
CREATE INDEX idx_order_claims_status ON order_claims (status, created_at);

CREATE TABLE order_claim_items (
    id             BIGSERIAL PRIMARY KEY,
    claim_id       BIGINT NOT NULL REFERENCES order_claims(id),
    order_item_id  BIGINT NOT NULL REFERENCES order_items(id),
    quantity       INTEGER NOT NULL,
    CONSTRAINT order_claim_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT uq_order_claim_items UNIQUE (claim_id, order_item_id)
);

ALTER TABLE order_items ADD COLUMN claimed_quantity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD CONSTRAINT order_items_claimed_quantity_range
    CHECK (claimed_quantity >= 0 AND claimed_quantity <= quantity);

-- 등급 산정용 구매확정 금액에서 빼야 하는 "돌려준 상품 금액(할인 몫 차감 후)"의 누적. 배송비는 이미 빠져 있다(V11).
ALTER TABLE orders ADD COLUMN returned_item_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT chk_orders_returned_item_amount_non_negative CHECK (returned_item_amount >= 0);

-- 재고 보상을 클레임 단위로도 남긴다. 주문 단위(PAYMENT·REFUND)는 주문당 1행, 클레임(CLAIM)은 클레임당 1행이다.
ALTER TABLE inventory_compensations ADD COLUMN claim_id BIGINT REFERENCES order_claims(id);
ALTER TABLE inventory_compensations DROP CONSTRAINT inventory_compensations_kind_check;
ALTER TABLE inventory_compensations ADD CONSTRAINT inventory_compensations_kind_check
    CHECK (kind IN ('PAYMENT', 'REFUND', 'CLAIM'));
ALTER TABLE inventory_compensations ADD CONSTRAINT inventory_compensations_claim_link
    CHECK ((kind = 'CLAIM') = (claim_id IS NOT NULL));
ALTER TABLE inventory_compensations DROP CONSTRAINT uq_inventory_compensations_order_kind;
CREATE UNIQUE INDEX uq_inventory_compensations_order_kind
    ON inventory_compensations (order_id, kind) WHERE kind <> 'CLAIM';
CREATE UNIQUE INDEX uq_inventory_compensations_claim
    ON inventory_compensations (claim_id) WHERE kind = 'CLAIM';
