-- order_items 에 판매자 스냅샷 추가 (gateway#212 0단계, order.api#13)
--
-- 왜 지금 하는가: 이 계획 전체에서 유일하게 되돌릴 수 없는 항목이다. 주문은 한 번 쌓이면
-- "그 주문을 누가 팔았는가"를 소급해서 채울 방법이 없다. 판매자가 자사 한 곳뿐인 지금은
-- 상수 하나를 넣는 작업이지만, 나중에 하면 그 사이 쌓인 주문은 영구히 판매자 미상으로 남는다.
-- (Order 에 채널 필드가 없어 소급 복구가 불가능했던 V5 와 같은 유형의 문제다.)
--
-- 스냅샷이지 참조가 아니다: 판매자가 나가거나 상호를 바꿔도 주문 시점의 사실은 그대로 남아야 한다.
-- 나중에 들어올 offer_id 는 성격이 다르다(참조라 오퍼가 삭제되면 끊긴다) — 둘 다 필요하고
-- 하나로 합치면 안 된다(order.api#14).

ALTER TABLE order_items ADD COLUMN seller_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_items ADD COLUMN seller_name VARCHAR(100);

-- 기존 행 백필 — 지금까지의 주문은 전부 자사 판매다.
UPDATE order_items SET seller_name = '포스셀렉트' WHERE seller_name IS NULL;

-- seller_name 도 NOT NULL 로 굳힌다. 판매자 미상 행이 다시 생기는 것을 막는 게 이 마이그레이션의 목적이다.
ALTER TABLE order_items ALTER COLUMN seller_name SET NOT NULL;

-- 판매자별 주문 조회(정산·CS)가 3P 전환 시 첫 번째로 필요해진다.
CREATE INDEX idx_order_items_seller_id ON order_items (seller_id);

COMMENT ON COLUMN order_items.seller_id IS
    '주문 시점 판매자 식별자 스냅샷. 1 = 자사(1P). 참조가 아니라 스냅샷이므로 FK 를 걸지 않는다.';
COMMENT ON COLUMN order_items.seller_name IS
    '주문 시점 판매자 상호 스냅샷. 표시·CS 용도이며 이후 상호가 바뀌어도 과거 주문은 안 바뀐다.';
