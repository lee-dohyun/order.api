-- OrderStatus enum이 CREATED/PAID 2개에서 7개(PREPARING/SHIPPED/DELIVERED/CANCELLED/REFUNDED 추가)로
-- 확장됐지만(#99) ddl-auto:update는 기존 CHECK 제약을 넓혀주지 않아 DB에는 여전히 좁은 제약이 남아있었음.
-- 배송 처리(PAID->SHIPPED 등)가 실제로 호출되면 제약 위반으로 실패하는 잠재 버그를 여기서 수정.
ALTER TABLE orders DROP CONSTRAINT orders_status_check;

ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('CREATED', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'));
