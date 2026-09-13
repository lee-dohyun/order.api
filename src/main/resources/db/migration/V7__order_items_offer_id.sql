-- order_items.offer_id — 주문 품목이 어떤 오퍼로 확정됐는가 (order.api#14, gateway#212 3단계)
--
-- product.api#31 이 카탈로그(Product/Variant)와 판매 단위(Offer: 누가 얼마에 파는가)를 분리했다.
-- 주문은 이제 가격·판매자를 오퍼 기준으로 확정하므로(product.api /internal/offers/resolve),
-- 그 확정에 쓰인 오퍼를 품목에 남긴다.
--
-- seller_id 와 성격이 반대라는 점이 핵심이다(V6 주석 참고):
--   seller_id  = 주문 시점 스냅샷. 판매자가 나가도 "누가 팔았는가"는 남아야 한다.
--   offer_id   = 참조. 오퍼가 끝나거나 지워지면 끊긴다 — 그래서 FK 를 걸지 않는다(catalogdb 는
--                별도 DB 라 걸 수도 없다). 둘 다 필요하고 하나로 합치면 안 된다.
--
-- NULL 허용인 이유: 이 컬럼 이전의 주문은 오퍼 개념 없이 variant 가격으로 확정됐다. 그 사실을
-- 소급해서 "자사 오퍼였다"고 채우면 없던 참조를 만들어내는 것이다 — 비워 둔다.
ALTER TABLE order_items ADD COLUMN offer_id BIGINT NULL;

-- 오퍼별 주문 조회(3P 정산·오퍼 성과)가 필요해지는 첫 지점.
CREATE INDEX idx_order_items_offer_id ON order_items (offer_id);

COMMENT ON COLUMN order_items.offer_id IS
    '주문 확정에 쓰인 product.api 오퍼 id. 참조이지 스냅샷이 아니며 FK 없음(별도 DB). 이 컬럼 도입 전 주문은 NULL.';

-- ---------------------------------------------------------
-- seller_name 을 VARCHAR(100) → VARCHAR(200) 으로 넓힌다.
--
-- 지금까지 이 값은 상수 '포스셀렉트' 였다. 이제 확정된 오퍼의 판매자 상호, 즉 product.api
-- sellers.name(VARCHAR 200)에서 온다. 100자를 넘는 상호의 판매자가 들어오면 주문 INSERT 가
-- 실패한다 — 1P 인 지금은 드러나지 않을 뿐이다. 넓히는 방향이라 기존 행에 영향이 없다.
-- ---------------------------------------------------------
ALTER TABLE order_items ALTER COLUMN seller_name TYPE VARCHAR(200);
