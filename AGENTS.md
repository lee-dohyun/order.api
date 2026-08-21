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

---

<!-- canon:begin sha=e5b6d5329e5d src=~/msa/AGENTS.md -->
## 공통 캐논 (모든 AI 도구 공통)

> **공통 캐논 (자동 주입 — 손으로 고치지 말 것).** 원본은 `~/msa/AGENTS.md`이고 이 블록은
> `~/msa/scripts/sync-agents-canon.sh`가 넣는다. 이 저장소만 클론해 도는 도구(Codex, CI,
> 워크스페이스를 저장소로만 연 IDE)는 `~/msa`를 볼 수 없으므로 규칙을 여기 함께 둔다.
> **규칙을 바꿀 때는 원본을 고치고 sync 스크립트를 다시 돌릴 것.**

### 현재 단계: 개발 단계 (운영 제약 유예)

**posselect는 아직 실사용자 트래픽이 없는 개발 단계다.** 사용자가 명시적으로 확인한 사항: 무중단 배포·롤링 안전성·하위 호환 유지 같은 운영 제약을 기본값으로 깔지 말고, 다운타임이 나거나 기존 데이터를 리셋해야 해도 **가장 단순한 방법으로 바로 변경·적용**한다.

- 아래 §3의 **expand-contract(2단계 제거) 규칙은 이 유예가 끝난 뒤 적용**한다. 개발 단계에서는 컬럼/테이블을 한 번에 갈아엎어도 된다. 단 **Flyway 마이그레이션으로만 바꾼다는 규칙 자체는 유예 대상이 아니다**(체크섬 사고 이력).
- 이 유예는 한시적이다. **실 서비스 시작 시점은 사용자가 별도로 통지**하며, 통지 이후에는 이 절을 삭제하고 §3을 그대로 적용한다.

## 3. 불변 개발 규칙 (위반 금지)

실제 사고에서 도출된 규칙이다. 근거 이슈를 함께 표기한다.

### DB / 스키마
- **스키마 변경은 Flyway 마이그레이션으로만.** `ddl-auto`는 `validate` 유지, `update` 복귀 금지 (posselect #104).
- 스키마 변경은 **expand-contract**: 컬럼/테이블 제거는 "새것 추가 → 코드 전환 → 다음 릴리스에서 제거" 2단계로.
- `@Enumerated(STRING)` enum에 값 추가 시 기존 CHECK 제약은 자동으로 안 넓혀짐 — 마이그레이션에 `ALTER` 포함할 것.
- 재고 음수 방지 CHECK, 멱등성 유니크 인덱스 등 **DB 레벨 제약은 애플리케이션 로직과 별개로 유지**한다 (posselect #211 V3).

### 트랜잭션 / 정합성
- **`@Transactional` 안에서 원격 HTTP 호출 금지**(보상 로직 없이). 로컬 롤백돼도 원격은 롤백 안 된다 (posselect #140, order.api 사례).
- **모든 상태 변경(쓰기) API는 멱등해야 한다.** 재시도/중복 호출이 이중 차감·이중 결제가 되지 않게 멱등성 키(예: orderId) 기반 dedup을 넣는다 (posselect #211).
- 클래스 레벨 `@Transactional(readOnly = true)`인 클래스에 쓰기 경로 추가 금지 — 전파 함정으로 UPDATE가 조용히 사라진다. 쓰기는 별도 클래스 또는 `REQUIRES_NEW` (posselect #211 롤백 사례).
- **트랜잭션 전파·멱등성 변경은 단위 테스트로 검증이 성립하지 않는다.** 실제 DB 상태 변화 실측(같은 키로 2회 호출 → 1회만 반영)으로 검증하고, 실측 후 데이터 원복까지 한 세트로 수행 (posselect #211).

### 보안 / 인가
- **사용자 식별 키는 Keycloak sub(`X-User-Id`)만.** 이메일은 변경 가능하므로 소유자 키로 쓰지 않는다 (posselect #210).
- 게이트웨이 주입 헤더(`X-User-*`)는 게이트웨이가 항상 **덮어써야** 한다 — 클라이언트가 보낸 값을 통과시키면 인증 우회가 된다 (msa #87).
- **리소스 조회/변경 API에는 소유자 검사 필수.** 소유자 불일치는 403이 아니라 **404**로 응답(순번 ID에서 403은 유효 ID 범위를 노출) (posselect #214).
- **새로 외부에 노출되는 리소스는 순번 PK(BIGSERIAL)를 URL/응답에 노출하지 말 것** — public_id(UUIDv7/ULID) 별도 부여 (posselect #214 재발 방지).
- 로그인 전 호출되는 경로를 추가하면 gateway `PUBLIC_EXACT_PATHS`에도 **반드시 같이** 등록 (라우팅과 인증 화이트리스트가 다른 저장소에 있음).
- 의존성 보안 패치(특히 Next.js/Spring)는 미루지 않는다 — store-front가 Next.js RCE(CVE-2025-66478)로 실제 침해 정황을 겪음 (msa #155).

### K8s / 배포
- stateful Deployment(PVC 사용)는 `strategy: Recreate`. 모든 PV는 `reclaimPolicy: Retain`. apply 전 `claimName`을 `kubectl get pvc`와 대조.
- 새 도메인은 기존 와일드카드 TLS 시크릿을 참조만 할 것 — Ingress에 `cert-manager.io/cluster-issuer` 어노테이션 추가 금지(와일드카드 인증서를 덮어쓰는 사고 이력).
- Ingress는 `leedohyun-com-ingress.yaml`/`posselect-com-ingress.yaml` 두 파일에 host만 추가. 서비스별 개별 Ingress 금지.
- CI는 main push → Docker 이미지 → CD(self-hosted runner) 즉시 프로덕션 반영. **문서만 바꿀 땐 커밋 메시지에 `[skip ci]`.**
- 여러 서비스에 걸친 변경은 **배포 순서**를 먼저 설계할 것(예: gateway → front → api 순서를 지켜야 게스트 결제가 안 끊기는 사례, posselect #210).
- `@posselect/ui` 변경은 Storybook만 자동 배포됨 — 소비 저장소 5개(customer/store/product/admin.front + posselect-shell)를 각각 재빌드해야 화면에 반영 (posselect #197).
- **`[skip ci]`는 커밋 제목뿐 아니라 본문에서도 인식된다.** 다른 커밋을 인용하려고 본문에 그 문자열을 적으면 배포가 조용히 건너뛰어진다 — 실제로 product.api 캐시 수정이 이 때문에 배포되지 않았다(gateway#204).
- **`[skip ci]`로 건너뛴 배포를 되살릴 때**: `docker-image.yml`에 `workflow_dispatch`만 추가하면 부족하다. `deploy` 잡의 `if:`가 `github.event_name == 'push'`로 고정돼 있어 수동 실행은 빌드만 하고 배포는 skip된다. 조건도 `push || workflow_dispatch`로 함께 풀 것(현재 product.api만 적용됨).
- **`pull_request` 워크플로는 PR head 브랜치의 파일로 돈다.** main의 워크플로를 고쳐도 이미 열려 있는 PR에는 반영되지 않고, `gh run rerun`은 원래 런의 워크플로 버전을 재사용한다. 수정 확인은 **브랜치를 리베이스한 뒤** 새 런으로 할 것.
- **Dependabot PR에는 저장소 시크릿이 전달되지 않는다.** 시크릿을 쓰는 스텝(`docker/login-action`)은 `if: github.event_name == 'push'`로 막고, `secrets.X`를 문자열에 끼워 넣는 곳(이미지 태그)은 `${{ secrets.X || 'ci-local' }}` 폴백을 줄 것 — 안 그러면 모든 Dependabot PR이 상시 실패해 PR 게이트 신호가 죽는다(gateway#209).

### CLI / 스크립팅
- **SSH를 통한 원격 bash 명령 실행 시 따옴표 이스케이프 주의:** PowerShell에서 변수(`$BODY`)를 따옴표 안에 넣어 원격 `curl` 등을 호출하면 bash 쪽에서 JSON 포맷 에러(`400 Bad Request` 등)가 발생하기 쉽다. 복잡한 인용부호(JSON 등)가 포함된 스크립트는 **전체를 Base64로 인코딩한 뒤 원격에서 디코딩하여 `bash`로 실행**한다 (`echo $b64 | base64 -d | bash`).

## 4. 작업 기록 및 관리 (GitHub & Memory) — 모든 도구 공통

모든 에이전트는 더 이상 Redmine을 사용하지 않으며, 아래의 **Task Execution Workflow**에 따라 GitHub Projects 및 Issues를 단일 소스(SSOT)로 활용합니다.

1. **명령 인식 (Command Recognition)**: 사용자의 의도와 작업 범위를 명확히 파악합니다.
2. **깃허브 이슈 확인 및 즉시 선점 (Check & Claim)**: 작업을 시작하기 전에 반드시 GitHub Project #2와 관련 저장소 이슈를 조회하여 동일/겹치는 작업이 이미 `In Progress`인지 확인합니다. 조회·클레임은 `~/msa/scripts/claim.sh <repo> <issue>` 한 줄로 수행한다(다른 세션이 잡고 있으면 스크립트가 막는다). 겹치는 항목이 없으면 **코드를 건드리기 전에** 해당 이슈를 만들거나 열어 Status를 `In Progress`로 즉시 전환합니다. **이 서버는 Claude Code/Codex/Antigravity 등 여러 AI 도구를 여러 세션으로 동시에 띄워 작업하는 환경이므로, "조회만 하고 착수 시점에 클레임하지 않는" 흐름으로는 다른 세션과 같은 소스/같은 작업이 겹칠 수 있다.** 조회 시 대상 항목이 이미 `In Progress`(특히 최근 갱신)이면 같은 작업을 새로 시작하지 말고 사용자에게 확인한다.
3. **작업 수행 (Task Execution)**: 파악된 작업을 순차적으로 수행하며 필요한 코드를 수정하거나 작성합니다.
4. **커밋 전 서브에이전트 검수 (Pre-commit Subagent Review)**: 코드를 커밋하기 전에 해당 레포지토리의 서브에이전트(또는 특화된 페르소나 규칙)를 활용하여 코드를 검수합니다.
5. **검수 후 주석 및 커밋 메시지 표준화 작성 (Standardized Comments & Commit Message)**: 검수가 완료된 코드에 대해 표준화된 주석을 달고, 일관된 양식의 커밋 메시지를 작성합니다.
6. **배포 (Deployment)**: 작성된 코드를 알맞은 파이프라인이나 환경으로 배포합니다.
7. **배포 후 정상 동작 확인 (Post-deployment Verification)**: 배포가 완료된 후 시스템이 정상적으로 동작하는지 반드시 테스트하고 검증합니다.

**지속적인 업데이트 (Continuous Updates)**: 위 과정을 진행하면서 진행 상황은 아래 §4-1 인계 프로토콜(`progress.sh`)로 이슈에 남깁니다. (예전 이 문단은 "내부 `task.md` 를 동기화하라"고 지시했으나, 그런 파일은 이 머신에 존재한 적이 없다 — 선언만 있고 실체가 없는 규칙이었으므로 제거했다.) 특히, **작업이 완전히 끝났을 때는 커밋 메시지(`Closes #이슈번호`)를 활용하거나 `gh issue close` 명령어를 통해 반드시 깃허브 이슈를 '완료(Closed)' 처리해야 합니다.**

**세션 격리 (Worktree, Check & Claim의 보완책)**: Check & Claim은 "같은 작업"의 중복 착수를 막는 조치이고, 이것과 별개로 여러 세션(도구 무관)이 **같은 저장소**(`~/git/<repo>`)의 공용 클론을 동시에 건드리면 서로 다른 작업이어도 파일/브랜치가 물리적으로 충돌할 수 있다. 저장소 작업을 시작할 때는 공용 클론을 직접 건드리기보다 별도 worktree를 기본으로 삼는다.
- Claude Code는 `EnterWorktree` 도구로 `.claude/worktrees/<repo>/<name>` 아래 자동 생성/전환한다 — 기본 경로를 그대로 쓴다.
- Codex/Antigravity 등 자체 worktree 기능이 없는 도구는 `git worktree add ../<repo>-<slug> -b <branch>`로 수동 생성하고, 작업 종료 후 `git worktree remove`로 정리한다.
- **각 저장소 `.gitignore`에 `.claude/worktrees/`가 반드시 있어야 한다.** 없으면 `git add -A`/`git add .` 한 번에 worktree 디렉터리 전체가 gitlink(모드 160000)로 커밋되어 origin까지 올라갈 수 있다 — 2026-08-21 `customer.front`에서 실제로 발생·이미 push된 상태로 확인됨(별도 정리 필요, 이 문서 편집만으로는 해결되지 않음).

## 4-1. 인계 프로토콜 — 다른 도구가 중간부터 이어받게 하기

세 도구(Claude Code / Codex / Antigravity)가 **전부 같은 GitHub 계정으로 커밋**하므로 assignee·커밋 author 로는 누가 무엇을 잡고 있는지 구분되지 않는다. 진행 상태를 공유할 수 있는 매체는 **이슈 코멘트 하나뿐**이다. 도구별 메모리(예: Claude의 `~/.claude/projects/.../memory`)나 로컬 파일에 적으면 다른 도구는 영원히 못 읽는다.

### 세션 시작 (도구 무관, 필수)

```bash
~/msa/scripts/session-start.sh      # 활성/스테일 클레임 + 저장소별 브랜치·미커밋·미푸시 상태
```

Claude Code 는 SessionStart 훅이 자동 실행한다(로컬 모드). **훅이 없는 도구는 세션의 첫 명령으로 직접 실행할 것.**

- Project #2 조회 결과는 세션 간 공유 캐시(기본 5분, `~/.cache/msa-agent/`)를 쓴다. `gh project` 계열은 전부
  GraphQL 이고 REST 와 한도가 분리돼 있는데 이 머신은 세션이 10~15개 동시에 뜬다 — 캐시가 없으면
  세션 시작 조회만으로 GraphQL 5000/hr 이 마른다(2026-08-21 실측: 58/5000 까지 떨어짐).
- **"조회 실패"와 "진행 중 작업 없음"은 다르다.** 한도가 소진되면 스크립트가 실패를 명시하고 낡은 캐시라도
  보여준다. 실패 표시가 뜨면 착수 전에 대상 이슈를 직접 열어 CLAIM 코멘트를 확인할 것 — 조회 실패를
  "아무도 안 잡았다"로 읽으면 그대로 중복 착수다.

### 코멘트 규격 (기계 판독용 첫 줄 + 사람이 읽는 본문)

| 종류 | 언제 | 명령 |
|------|------|------|
| `CLAIM` | 코드를 건드리기 **전** | `~/msa/scripts/claim.sh <repo> <issue>` |
| `PROGRESS` | 의미 있는 단위마다 | `~/msa/scripts/progress.sh <repo> <issue> "한 일\|다음 단계\|검증 방법"` |
| `HANDOFF` | 중단하거나 끝낼 때 | `~/msa/scripts/handoff.sh <repo> <issue> "남은 일/위험" [--done]` |
| `TAKEOVER` | 남의 스테일 클레임을 인수할 때 | `~/msa/scripts/claim.sh <repo> <issue> --takeover` |

- 코멘트 첫 줄은 ```CLAIM tool=... branch=... started=...``` 형태로 고정된다. 손으로 쓰지 말고 스크립트를 쓸 것 — 포맷이 깨지면 다른 세션의 클레임 판정이 틀린다.
- **실행 도구 식별은 자동이다 — 세션마다 뭘 설정할 필요 없다.** 스크립트가 `/proc` 조상 체인에서 이 셸을 띄운 주체(ccd-cli / codex / antigravity IDE 서버 …)를 찾아 판별한다. 환경변수는 자식으로 새기 때문에(Claude 세션 안에서 codex 를 띄우면 `CLAUDECODE` 를 물려받는다) 조상 체인을 먼저 본다.
  - 판별 결과가 `unknown` 으로 남는 도구가 생기면, 그때마다 `AGENT_TOOL` 을 치지 말고 **`~/msa/scripts/lib/agent-protocol.sh` 의 `_agent_ancestry_scan()` 에 패턴 한 줄을 추가**한다(한 번만 하면 그 도구의 모든 세션에 적용된다).
  - 일회성으로 다르게 기록해야 할 때만 `AGENT_TOOL=... ` 또는 `--tool` 로 덮어쓴다.

### 스테일 클레임 만료 (2시간)

마지막 프로토콜 코멘트가 **2시간**(`MSA_CLAIM_STALE_SECONDS`) 넘게 없으면 그 클레임은 만료된 것으로 보고 `--takeover` 로 인수할 수 있다. 반납되지 않은 `In Progress` 가 영원히 남아 다른 세션을 막는 문제를 이 규칙으로 푼다(2026-08-21 실측: In Progress 11건 중 클레임 기록이 있는 것 0건, 일부는 며칠째 정지).

### 인계 가능 = 원격에 push된 상태

로컬 worktree 의 브랜치는 다른 도구·다른 세션 눈에 **보이지 않는다.** 작업을 중단할 때는 `wip:` 커밋이라도 push 한 뒤 `handoff.sh` 를 실행한다(미푸시 상태로 인계하려 하면 스크립트가 막는다). `--done` 없이 실행하면 Status 는 `In Progress` 로 남고 클레임만 반납되어, 다른 도구가 `--takeover` 로 바로 이어받는다.

### 어디에 무엇을 쓰나

| 내용 | 위치 |
|------|------|
| 진행 중 상태·다음 단계·인계 정보 | **이슈 코멘트**(위 프로토콜) |
| 확정된 개발 규칙 | `~/msa/AGENTS.md` (이 문서) |
| 사고 기록·ADR 등 장기 지식 | GitHub Wiki(gateway/order.api) |
| 도구 자신의 작업 효율용 메모 | 각 도구의 메모리 — **다른 도구는 못 읽는다는 전제로만 사용** |

## 5-1. 자동 점검 장치 — 도구 무관 (2026-08-21 배선, 같은 날 도구 무관화)

규칙을 문서로만 선언하지 않고 실제로 강제하는 장치다. **어떤 AI 도구도 이 장치들을 우회하지 말 것** —
우회하면 이 문서의 규칙이 다시 선언으로만 남는다.

- **`<저장소>/scripts/verify.sh`** — push 전 검증의 **단일 진입점**. 스택을 자동 판별해
  `./gradlew test` 또는 `npm run typecheck/lint/test` 를 돌리고, `scripts/verify.d/*.sh` 추가 검사를 실행한다.
  문서·도구 설정만 바뀐 push 는 스스로 건너뛴다. 우회는 `MSA_SKIP_VERIFY=1`, 우회했다면 그 사실을 보고/이슈에 남길 것.
  - 호출자 3곳이 **같은 스크립트**를 부른다: `.githooks/pre-push`(도구 무관) / `.claude/hooks/pre-push-verify.sh`(Claude) / CI.
  - `.githooks/pre-push` 는 클론마다 `~/msa/scripts/bootstrap-hooks.sh` 를 1회 돌려 `core.hooksPath` 를 걸어야 활성화된다
    (이 설정은 커밋되지 않는 로컬 설정이다). **새 클론·새 머신에서 제일 먼저 할 일.**
  - 2026-08-21 이전에는 검증이 `.claude/hooks/` 아래에만 있어 Claude 이외의 도구가 push 하면 아무 검증도 걸리지 않았다.
- **`<저장소>/AGENTS.md` 의 `<!-- canon:begin -->` 블록** — 이 문서의 공통 규칙이 각 저장소에 주입된 사본이다.
  `~/msa` 는 git 저장소가 아니라 저장소만 클론해 도는 도구(Codex, CI, IDE)는 원본을 읽을 수 없기 때문이다.
  **손으로 고치지 말 것.** 규칙 변경은 이 문서를 고치고 `~/msa/scripts/sync-agents-canon.sh` 를 다시 돌린다
  (`--check` 로 어긋난 저장소를 찾는다). `CLAUDE.md`/`GEMINI.md` 는 `AGENTS.md` 심링크다.
- **`<저장소>/.claude/agents/*.md`** — 저장소별 가드(게이트웨이 화이트리스트, Flyway, 트랜잭션/멱등성,
  캐시 무효화, 디자인 토큰, 셸 계약). Claude Code는 자동 위임하고, **다른 도구는 해당 파일을 읽어 같은 점검을 수행할 것.**
- **결정적 검사 스크립트** — `check-token-mirror.sh`(posselect-ui), `check-i18n-keys.sh`/`check-mermaid.sh`
  (architecture), `~/msa/scripts/check-architecture-drift.sh`. LLM 없이 동작하므로 어떤 도구에서든 그냥 실행하면 된다.
- **CI** — 각 저장소 `pr-check.yml`(PR 단계 게이트), `claude-review.yml`(자동 리뷰, `ANTHROPIC_API_KEY` 필요).
  단 `pr-check.yml` 은 `pull_request` 에서만 돈다 — **main 직push 는 CI 게이트가 없고 곧 배포다.**
  그래서 push 전 검증은 `.githooks/pre-push` 가 유일한 방어선이다.

작업 기록은 `msa-work-log` 스킬(Claude Code) 또는 `~/.claude/skills/msa-work-log/SKILL.md`(다른 도구는 이 파일을
읽고 같은 절차 수행)를 따른다. **Project에 저장소 미연결 Draft issue를 만들지 말 것** — 2026-08-17 이관 때
중복 카드 210여 건이 생긴 원인이다. 항상 실제 저장소 Issue를 만들어 Project #2에 연결한다.
<!-- canon:end -->
