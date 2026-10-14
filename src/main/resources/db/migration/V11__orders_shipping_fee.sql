-- orders 에 배송비 스냅샷 (gateway#322 "주문 합계에 배송비 반영")
--
-- 상품 판매 정책(product.api product_policies)에 배송비가 있어도 주문이 그 값을 읽지 않아서,
-- 유료배송 상품을 사도 결제 금액은 상품 금액뿐이었다.
--
-- 이제 주문 생성 시점에 product.api 가 계산한 배송비를 받아 total_price 를
-- "상품 합계 - 등급 할인 + 배송비"로 확정하고 그 금액을 여기에 남긴다. 스냅샷인 이유는
-- discount_amount(V9)와 같다 — 판매 정책은 판매자가 언제든 바꾸므로, 나중에 product.api 를 다시
-- 조회해서는 "이 주문이 왜 이 금액이었나"를 복원할 수 없다.
--
--   shipping_fee = 청구한 배송비(원). 계산 규칙(판매자별 묶음 1회, 기준액은 할인 전 상품 금액)은
--                  product.api ShippingFeeCalculator 에만 있다.
--   상품 합계    = total_price + discount_amount - shipping_fee 로 복원한다.
--
-- 기존 주문은 배송비 없이 확정됐으므로 0 이다(소급해서 채우지 않는다).
ALTER TABLE orders ADD COLUMN shipping_fee NUMERIC(12,2) NOT NULL DEFAULT 0;

-- 음수 배송비는 어떤 정책에서도 올바르지 않다. 애플리케이션 검증과 별개로 DB 가 막는다(캐논 §3).
ALTER TABLE orders ADD CONSTRAINT chk_orders_shipping_fee_non_negative CHECK (shipping_fee >= 0);

COMMENT ON COLUMN orders.shipping_fee IS
    '주문 시점에 청구한 배송비(원) 스냅샷. total_price 는 이미 이 금액이 더해진 결제 금액이다.';
