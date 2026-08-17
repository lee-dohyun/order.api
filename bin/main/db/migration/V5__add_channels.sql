-- 채널 테이블 신설 (product.api와 동기화)
CREATE TABLE channels (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    domain     VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 기본 채널(종합몰) 1건 삽입
INSERT INTO channels (id, name, domain) VALUES (1, '종합몰', 'posselect.com');

-- 주문 테이블에 채널 ID 추가
ALTER TABLE orders ADD COLUMN channel_id BIGINT;
UPDATE orders SET channel_id = 1;
ALTER TABLE orders ALTER COLUMN channel_id SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT fk_orders_channel FOREIGN KEY (channel_id) REFERENCES channels(id);
