-- =========================================================
-- 교환 (gateway#320, 2026-10-14 결정)
--
-- 같은 상품의 같은 가격 옵션으로만, 상품 불량·파손·오배송일 때만 받는다(단순 변심 교환은 실결제 전까지 받지 않는다).
-- 흐름: REQUESTED → 관리자 승인 시 새 옵션 재고 차감·회수품 재고 복원 → APPROVED(교환 발송 대기)
--       → 직원이 교환 운송장 등록 → COMPLETED. 환불은 없다.
-- =========================================================
ALTER TABLE order_claims DROP CONSTRAINT order_claims_type_check;
ALTER TABLE order_claims ADD CONSTRAINT order_claims_type_check CHECK (type IN ('CANCEL', 'RETURN', 'EXCHANGE'));
ALTER TABLE order_claims DROP CONSTRAINT order_claims_status_check;
ALTER TABLE order_claims ADD CONSTRAINT order_claims_status_check
    CHECK (status IN ('REQUESTED', 'APPROVED', 'COMPLETED', 'REJECTED'));

ALTER TABLE order_claims ADD COLUMN exchange_carrier VARCHAR(50);
ALTER TABLE order_claims ADD COLUMN exchange_tracking_number VARCHAR(50);
ALTER TABLE order_claims ADD COLUMN exchange_shipped_at TIMESTAMP(6);

-- 교환으로 보낼 옵션(같은 상품의 variant)과 신청 시점의 옵션 표시 이름 스냅샷.
ALTER TABLE order_claim_items ADD COLUMN exchange_variant_id BIGINT;
ALTER TABLE order_claim_items ADD COLUMN exchange_option_label VARCHAR(200);
