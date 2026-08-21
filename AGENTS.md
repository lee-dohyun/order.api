# order.api AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다.
> 원칙이 충돌하면 캐논이 이긴다. 아래는 이 저장소만의 특이사항이다.
>
> `CLAUDE.md`는 이 파일(`AGENTS.md`)로의 심링크다 — 둘 중 아무거나 고쳐도 같은 파일이다.

## 이 저장소는 무엇인가

`order.api`는 PosSelect 쇼핑몰의 **주문·결제·환불·배송 도메인** 서비스다. Spring Boot 3.5.3 / Java 21 /
Gradle. Postgres(`ordersdb`)를 쓰고 Redis는 쓰지 않는다.

**재고와 가격은 이 저장소가 갖고 있지 않다.** 주문 생성 시 `ProductApiClient`가 product.api의
`/internal/variants/resolve`로 가격·상품명을 확정받고, 결제 확정 시 `/internal/inventory/deduct`로
재고를 차감시킨다. 클러스터 내부 DNS로만 호출하므로 게이트웨이/인증을 거치지 않는다.
결제는 **아직 mock**이다(`payOrder`는 항상 성공 처리 후 `Payment(..., "MOCK")` 저장) — 실 PG 연동 시
여기만 교체하는 구조다.

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트 + build/libs/*.jar
./gradlew test           # 테스트만 (JUnit 5 + Mockito)
./gradlew compileJava    # 컴파일만 (빠른 확인용)
./gradlew test --tests com.dh.order.service.OrderServiceTest
./gradlew bootRun        # 로컬 실행 (Postgres 필요)
```

푸시 전에 `./gradlew test`를 로컬에서 직접 돌려 성공을 확인한다. CI 에러에만 의존하지 말 것.
`.claude/hooks/pre-push-verify.sh`가 `git push` 직전에 이 검사를 강제한다(정당한 사유가 있으면
`CLAUDE_SKIP_PUSH_VERIFY=1`).

**Node/npm 명령은 이 저장소에 없다.** 프론트엔드 저장소의 `npm run typecheck` / `npm run test` 같은 지시를
이 저장소에 적용하지 말 것 — 여기서 대응하는 검증은 전부 `./gradlew`다.

CI/CD(`.github/workflows/docker-image.yml`): `main` push → `./gradlew build` → Docker Hub 푸시 → Trivy 스캔 →
self-hosted runner(`k3s-home`)가 `kubectl set image deployment/order-api -n customer`로 **즉시 프로덕션
반영**(`rollout status --timeout=600s`). 문서/설정만 바꾸는 커밋은 커밋 메시지 끝에 `[skip ci]`를 붙인다.

## 서브에이전트 가드 (`.claude/agents/`)

이 저장소에는 실제 사고에서 뽑은 점검 두 개가 서브에이전트로 들어 있다. 해당 상황이면 **먼저 돌리고**
그 결과를 커밋 전 검수(캐논 §4-4)로 삼는다.

- **`tx-idempotency-reviewer`** — `OrderService`(또는 새 서비스)의 쓰기 경로를 추가/수정할 때,
  `@Transactional`이나 전파 속성을 건드릴 때, `ProductApiClient` 호출을 추가할 때,
  `OrderController`가 `Requester`를 만드는 방식을 바꿀 때. "재고가 두 번 빠졌다 / 결제가 두 번 됐다 /
  환불했는데 재고가 안 돌아왔다 / UPDATE가 조용히 안 됐다" 제보에도 이걸 먼저 돌린다.
- **`flyway-migration-guard`** — `src/main/resources/db/migration/` 아래 파일을 만들거나 고칠 때,
  `domain`/`payment` 엔티티에 필드나 enum 값이 늘거나 줄 때, 부팅이 `Schema-validation` /
  `Migration checksum mismatch`로 실패할 때, 상태 전이가 CHECK 제약 위반으로 실패할 때.

## 작업 기록 (GitHub)

캐논 §4를 따른다. 착수 전에 GitHub Project #2("PosSelect 쇼핑몰 웹 애플리케이션 구축")와 이 저장소의
Issue를 조회해 겹치는 작업이 이미 `In Progress`인지 확인하고, 없으면 **코드를 건드리기 전에** 이슈를
`In Progress`로 선점한다(여러 AI 도구 세션이 동시에 도는 환경이다).

- **저장소에 연결되지 않은 Draft issue를 만들지 말 것.** 반드시 `gh issue create -R lee-dohyun/order.api`로
  실제 저장소 Issue를 만든 뒤 `gh project item-add 2 --owner lee-dohyun --url <issue-url>`로 Project #2에
  연결한다. Draft 카드는 저장소·커밋·PR과 이어지지 않아 추적이 끊기고, 과거 중복 카드 210여 건이 쌓인
  원인이 이것이었다.
- 작업이 끝나면 커밋 메시지의 `Closes #<번호>` 또는 `gh issue close`로 반드시 닫는다.
- `gh`는 `~/.local/bin/gh` 풀 경로로 호출한다.

## 트랜잭션 — 이 저장소 사고 위험 1순위

`OrderService`는 **클래스 레벨 `@Transactional(readOnly = true)`** 다. 여기에 자기 `@Transactional` 없이
쓰기 메서드를 추가하면 읽기 전용 트랜잭션에 합류해 **UPDATE가 예외도 로그도 없이 사라진다**
(캐논 §3, posselect #211).

- `createOrder`는 `Propagation.NOT_SUPPORTED`다. 가격 확정이 원격 HTTP(5초 타임아웃)라 트랜잭션 안에서
  기다리면 커넥션을 잡은 채 네트워크를 기다리게 되기 때문이고, 저장은 `orderRepository.save()`가 자체
  트랜잭션으로 한다. **이걸 평범한 `@Transactional`로 "정리"하지 말 것** — 커넥션 홀드와 readOnly 함정을
  동시에 되살린다.
- `payOrder` / `refundOrder` / `createShipment` / `markDelivered`는 각자 `@Transactional`을 달고 있다.
  새 쓰기 메서드를 옆에 추가하면서 이걸 빠뜨리는 게 정확히 #211의 재현 경로다. 새 쓰기 경로는 가급적
  별도 서비스로 분리한다.

### 원격 호출과 보상 트랜잭션 (알려진 미해결 결함)

- **`payOrder`에는 보상 로직이 없다.** `@Transactional` 안에서 `productApiClient.deductInventory(...)`를
  호출하고, 그 뒤에 `PAID` 전이 → `Payment` 저장 → `notificationService.notifyPaid(...)`가 이어진다.
  차감 이후 무엇이든 실패하면 **재고는 빠진 채 주문은 PAID가 아닌 상태**로 남고 아무도 되돌리지 않는다.
  `payOrder`를 건드리는 변경은 이걸 해결하거나, 해결하지 않는다는 사실을 명시해야 한다(캐논 §3).
- 메일 발송도 트랜잭션 **안**에 있다. `OrderNotificationService.notifyPaid`가 `MailException`을 흡수해서
  결제를 실패시키지는 않지만, SMTP를 기다리는 동안 DB 트랜잭션을 붙잡고 있다. 롤백 불가능한 부수효과는
  커밋 이후로 빼는 게 맞다.
- **환불의 재고 복원은 "없는" 게 아니라 "약하다".** `OrderController.refund`가 트랜잭션 밖에서
  ① 아이템 조회 → ② `orderService.refundOrder`(REFUNDED 전이 + Refund 저장, 커밋) →
  ③ `productApiClient.restoreInventory`를 `try/catch`로 호출하는데, 실패 시 하는 일이
  `logger.error("환불은 성공했으나 재고 복원 호출 실패 - 수동 복원 필요")` 뿐이다. 재시도도 큐도 없다.
  환불 로직을 건드릴 때마다 이 점을 짚고, 고친다면 트랜잭션을 넓히는 게 아니라 재시도/아웃박스로 간다.

### 멱등성

- `payOrder`의 방어는 `if (order.getStatus() != CREATED) throw`뿐이다. 상태 가드는 필요하지만 동시성에는
  **불충분**하다(두 요청이 동시에 `CREATED`를 읽을 수 있다). 실제로 막는 건 DB 유니크 제약이나
  `SELECT ... FOR UPDATE`인데, 이 저장소의 `orders`/`payments`에는 아직 없다.
- 실질적인 중복 차감 방어는 product.api 쪽 `V3__inventory_deduct_idempotency.sql`의 부분 유니크 인덱스
  `(order_id, inventory_id) WHERE type = 'ORDER_DEDUCT'`이고, **멱등성 키는 주문 ID**다
  (`deductInventory`가 `{"orderId": ..., "items": [...]}`를 보낸다). 여기서 보내는 키를 바꾸면 방어가
  요란하게 깨지는 게 아니라 **조용히 사라진다.**
- `createShipment`의 `findByOrderId(...).isPresent()` 중복 검사도 같은 read-then-write 경합이 있다.

## 인증과 소유권

- **이 저장소는 게이트웨이의 `X-User-*` 헤더를 읽지 않는다.** `OrderController.extractRequester`가
  `Authorization: Bearer` 토큰을 받아 직접 검증한다 — 고객은 `CustomerJwtVerifier`(Keycloak `customer`
  realm JWKS로 서명·만료·issuer 확인), 관리자는 `AdminJwtVerifier`(`staff` realm). 소유자 키는
  `claims.getSubject()`(Keycloak sub)이고 `orders.customer_id`에 들어간다(V4). 새 접근 경로도 헤더를
  믿지 말고 같은 검증을 타야 한다.
- `Order.isAccessibleBy`는 `customerId` → (V4 백필 전 레거시 행 한정) `customerEmail` → `guestToken`
  순이다. **이메일 폴백을 넓히지 말 것** — 이메일은 auth.api에서 변경 가능해 소유권이 끊긴다
  (캐논 §3, posselect #210). 폴백은 백필이 끝나면 제거될 경로다.
- 게스트 주문의 유일한 접근 수단인 `guestToken`은 **생성 응답에서 한 번만** 내려가고
  (`toResponse(saved, saved.getGuestToken())`), 이후 조회에는 `null`이 실린다. 클라이언트는
  `X-Order-Guest-Token` 헤더로 되돌려 보낸다. 다른 응답에 이 값을 싣지 말 것.
- **소유자 불일치는 403이 아니라 404**다. `loadAccessible`이 일부러 `NoSuchElementException`을 던지는데,
  주문 ID가 순번이라 403을 주면 "존재하는 ID 범위"가 새기 때문이다(캐논 §3, posselect #214).
  새 접근자에서도 이 동작을 유지한다.
- **가격·productId·productName을 클라이언트에서 받지 말 것.** `createOrder`는 셋 다
  `ProductApiClient.resolveVariants` 응답으로 덮어쓰고, 카탈로그가 돌려주지 않았거나 비활성인 variant는
  주문 자체를 거절한다. 임의 금액 주문이 가능했던 게 posselect #232다.

## 스키마 변경은 Flyway로만

- 운영 설정은 `ddl-auto: validate` + `flyway.enabled: true`(`baseline-on-migrate: true`)다.
  **`ddl-auto: update`로 되돌리지 말 것**(캐논: posselect #104).
- 마이그레이션은 `src/main/resources/db/migration/`에 **V1~V5가 실재한다.** V1 기준 스키마,
  V2 `orders_status_check` 확장, V3 payments/refunds, V4 주문 소유자 키(customer_id/guest_token),
  V5 채널. 다음 번호는 V6.
- **이미 배포된 마이그레이션 파일은 수정하지 않는다** — checksum 불일치로 부팅이 막힌다. 뒤에 새 버전을
  덧붙여 고친다.
- **`@Enumerated(STRING)` 필드가 4개다**(`Order.status`, `Shipment.status`, `Payment.status`,
  `Refund.status`). enum에 값을 추가해도 기존 CHECK 제약은 안 넓혀진다 — V2가 정확히 그 사고의 수습본이니
  (`OrderStatus`가 2개에서 7개로 늘었는데 제약은 그대로였다) 같은 모양의 `DROP/ADD CONSTRAINT`를 복사할 것.
- `baseline-on-migrate: true`라 운영 DB에서 V1은 실행된 적이 없다. 사전-Flyway 테이블의 제약 이름을
  하드코딩해 `DROP`하지 말고 `pg_constraint`에서 찾아 지운다(product.api가 이걸로 배포가 막힌 적 있다).
- V4가 `customer_email`을 남겨둔 건 관리자 화면 표시와 미백필 행 폴백 때문이다. expand-contract대로
  백필이 끝난 뒤에 폴백만 제거한다.

## 테스트가 무엇을 증명하고 무엇을 못 하는가

**이 저장소에는 `src/test/resources`도, 테스트용 데이터소스도, Testcontainers도 없다.** `build.gradle`의
테스트 의존성은 `spring-boot-starter-test`와 `spring-restdocs-mockmvc`뿐이고, 모든 테스트가 Mockito
단위 테스트다(`OrderServiceTest`는 `@ExtendWith(MockitoExtension.class)`).

즉 `./gradlew test`는 **트랜잭션 전파도 멱등성도 마이그레이션 누락도 증명하지 못한다.** 목 리포지토리는
읽기 전용 트랜잭션이 버렸을 save를 "성공"으로 보고한다(캐논 §3가 명시). 그런 변경은 배포된 엔드포인트를
같은 키로 두 번 호출하고 **행을 다시 읽어** 1회만 반영됐는지 확인한 뒤 데이터를 원복하는 것까지가 한 세트다.
로컬에서 더 강한 근거가 필요하면 product.api의 `InventoryDeductionIntegrationTest`(`@SpringBootTest` +
`@Testcontainers` + `@ServiceConnection`, `JdbcTemplate`으로 커밋된 행 검증)를 그대로 복사해 오는 게 답이다.

## i18n / 금액 표기

- `messages*.properties`(ko/en/zh/ja) + `fallback-to-system-locale: false`. 미지원 언어가 서버 JVM 로케일을
  따라가지 않고 한국어 번들로 떨어지게 하려는 설정이다. 사용자에게 보일 문구는 예외에 담지 말고 메시지
  키로 넘긴다(`OrderStateException`은 메시지 키만 들고 다니므로 cause를 못 싣는다 — 원인은 호출부에서
  로그로 남길 것).
- 금액은 `MoneyFormatter` + `app.base-currency`(기본 KRW) 단일 저장이다. 주문번호를 메시지 인자로 넘길
  때는 **문자열로** 넘긴다 — 숫자로 넘기면 `MessageFormat`이 자릿수 구분자를 붙여 "1,234"가 된다.

## 관련 서비스

- [gateway](../gateway) — 단일 진입점. 다만 이 서비스는 헤더가 아니라 토큰을 직접 검증한다(위 참고).
- [product.api](../product.api) — 가격 확정(`/internal/variants/resolve`)과 재고 차감·복원
  (`/internal/inventory/deduct|restore`)의 상대편. 이 두 계약이 바뀌면 `ProductApiClient`를 같이 고쳐야 한다.
- [customer.front](../customer.front) — 주문/결제 화면. 게스트 토큰을 저장하고 되돌려 보내는 쪽.
- [admin.front](../admin.front) — 전체 주문 조회·운송장 등록·배송완료·환불의 유일한 정상 경로(staff realm).
