# order.api

주문을 생성/조회하는 Spring Boot 3.5 / Java 21 서비스. `customer` 네임스페이스의 PostgreSQL(`ordersdb`)에 주문을 저장한다.

catalogdb(product.api)와는 별개 DB라 상품 정보에 FK로 접근하지 않는다 — 주문 생성 요청에 상품명/가격을 클라이언트(장바구니)가 스냅샷으로 함께 보내고, 그대로 저장한다. 이후 상품 가격이 바뀌어도 과거 주문 금액은 바뀌지 않는다.

## 테이블

- `orders(id, orderer_name, orderer_phone, shipping_address, total_price, status, created_at)`
- `order_items(id, order_id, product_id, product_name, price, quantity)`

스키마는 `spring.jpa.hibernate.ddl-auto: update`로 애플리케이션 기동 시 자동 생성된다.

## API

- `POST /api/orders` — 주문 생성 (`items`에 productId/productName/price/quantity 배열)
- `GET /api/orders/{id}` — 단일 주문 조회

## 환경 변수

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT` (기본값은 `application.yml` 참고, customer 네임스페이스의 `orders-postgres` 기준).

## 실행

```bash
./gradlew build
./gradlew bootRun
```
