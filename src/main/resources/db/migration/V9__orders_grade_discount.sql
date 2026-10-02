-- orders 에 회원 등급 할인 스냅샷 (gateway#82 "등급 혜택 적용")
--
-- auth.api member_grades.discount_rate(GENERAL 0 / SILVER 2 / GOLD 5 / VIP 10 %)는 V1 부터 있었지만
-- 주문이 그 값을 읽지 않아서, 등급이 올라도 결제 금액은 그대로였다.
--
-- 이제 주문 생성 시점에 등급을 조회해 total_price 를 "상품 합계 - 등급 할인"으로 확정하고, 그 근거를
-- 여기에 남긴다. 스냅샷인 이유는 order_items.seller_id(V6)와 같다 — 등급은 매월 재산정되고 할인율은
-- 정책 테이블이라 바뀐다. 나중에 auth.api 를 다시 조회해서는 "이 주문이 왜 이 금액이었나"를 복원할 수 없다.
--
--   discount_amount      = 깎인 금액(원). 상품 합계는 total_price + discount_amount 로 복원한다.
--   grade_code           = 할인 근거가 된 등급 코드. 게스트·등급 조회 실패·이 컬럼 이전 주문은 NULL.
--   grade_discount_rate  = 그 시점의 할인율(%). grade_code 와 함께만 채워진다.
--
-- 기존 주문은 할인 없이 확정됐으므로 discount_amount 0, 등급은 NULL 로 둔다(소급해서 채우지 않는다).
ALTER TABLE orders ADD COLUMN discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN grade_code VARCHAR(20) NULL;
ALTER TABLE orders ADD COLUMN grade_discount_rate NUMERIC(5,2) NULL;

-- 할인이 상품 합계를 넘어 결제 금액이 음수가 되는 것은 어떤 정책에서도 올바르지 않다.
-- 애플리케이션 계산과 별개로 DB 가 막는다(캐논: DB 레벨 제약은 로직과 별개로 유지).
ALTER TABLE orders ADD CONSTRAINT chk_orders_discount_non_negative CHECK (discount_amount >= 0);
ALTER TABLE orders ADD CONSTRAINT chk_orders_total_non_negative CHECK (total_price >= 0);

COMMENT ON COLUMN orders.discount_amount IS
    '회원 등급 할인액(원). total_price 는 이미 이 금액이 빠진 결제 금액이다.';
COMMENT ON COLUMN orders.grade_code IS
    '주문 시점 회원 등급 코드 스냅샷(auth.api member_grades.code). 게스트·조회 실패·도입 전 주문은 NULL.';
COMMENT ON COLUMN orders.grade_discount_rate IS
    '주문 시점 등급 할인율(%) 스냅샷. grade_code 와 함께만 채워진다.';
