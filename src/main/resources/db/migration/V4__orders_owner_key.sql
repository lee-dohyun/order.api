-- =========================================================
-- 주문 소유자를 불변 키(Keycloak sub)로 식별한다. (Redmine posselect #210, #214)
--
-- 기존에는 customer_email 이 회원-주문을 잇는 유일한 키였는데, auth.api 의
-- PUT /api/auth/me 로 이메일을 바꿀 수 있어서 변경 순간 주문 이력이 주인을 잃었다.
-- 옛 이메일과 새 이메일의 매핑을 아무도 기록하지 않으므로 사후 복구도 불가능했다.
--
-- customer_email 은 지우지 않는다 - 관리자 화면 표시와 아직 백필되지 않은 레거시
-- 행의 조회 폴백에 계속 쓰인다. 백필이 끝나면 조회 폴백만 제거하면 된다.
-- =========================================================
ALTER TABLE orders ADD COLUMN customer_id VARCHAR(36);

-- 게스트(비로그인) 주문은 소유자 식별 수단이 아예 없어서 주문번호만 알면 누구나
-- 열람/결제가 가능했다. 생성 시 발급한 이 토큰을 아는 클라이언트만 접근할 수 있게 한다.
ALTER TABLE orders ADD COLUMN guest_token VARCHAR(36);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);

-- 백필 안내: 기존 행은 customer_id 가 NULL 이라 조회가 customer_email 폴백을 탄다.
-- Keycloak admin API 로 distinct customer_email -> sub 를 조회해 UPDATE 하면
-- 폴백 없이도 동작한다. 현재 데이터는 이메일 변경 이력이 없어 매핑이 정확하지만,
-- 이메일 변경이 한 번이라도 일어난 뒤에는 이 백필이 틀린 답을 낸다.
