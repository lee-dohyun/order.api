package com.dh.order.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dh.order.config.AdminJwtVerifier;
import com.dh.order.config.AdminPrincipal;
import com.dh.order.config.CustomerJwtVerifier;
import com.dh.order.dto.OrderDtos.CreateShipmentRequest;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.RefundResponse;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.service.OrderService;
import com.dh.order.service.InventoryCompensator;
import com.nimbusds.jwt.JWTClaimsSet;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    /** 게스트 주문 접근 토큰. 주문 생성 응답으로 받은 값을 클라이언트가 되돌려 보낸다. */
    private static final String GUEST_TOKEN_HEADER = "X-Order-Guest-Token";

    /** 주문 생성 멱등 키. 클라이언트가 주문 시도마다 만든 값(UUID)을 싣는다. */
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** orders.idempotency_key 가 VARCHAR(64)다. 너무 짧은 키는 다른 시도와 겹칠 수 있어 받지 않는다. */
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /** admin.front lib/menu.ts 의 "주문 관리" requiredRoles 와 같은 값이어야 한다. */
    private static final String ORDER_MANAGER = "ORDER_MANAGER";

    private final OrderService orderService;
    private final com.dh.order.claim.ClaimService claimService;
    private final AdminJwtVerifier adminJwtVerifier;
    private final CustomerJwtVerifier customerJwtVerifier;
    private final InventoryCompensator inventoryCompensator;
    private final com.dh.order.service.OrderNotificationService notificationService;

    public OrderController(OrderService orderService, AdminJwtVerifier adminJwtVerifier,
                           CustomerJwtVerifier customerJwtVerifier, InventoryCompensator inventoryCompensator,
            com.dh.order.claim.ClaimService claimService,
            com.dh.order.service.OrderNotificationService notificationService) {
        this.notificationService = notificationService;
        this.claimService = claimService;
        this.orderService = orderService;
        this.adminJwtVerifier = adminJwtVerifier;
        this.customerJwtVerifier = customerJwtVerifier;
        this.inventoryCompensator = inventoryCompensator;
    }

    /**
     * 주문 생성. {@code Idempotency-Key} 를 보내면 같은 키의 재시도는 새 주문을 만들지 않고 처음 만든
     * 주문을 그대로(201) 돌려받는다 — 응답을 못 받은 클라이언트가 안심하고 다시 보낼 수 있게 한다(gateway#306).
     * 키가 없으면 요청마다 주문을 만든다.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = "X-Channel", defaultValue = "1") Long channelId,
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY_HEADER + " 는 영문·숫자·'-'·'_' 16~64자여야 한다");
        }
        Requester requester = extractRequester(authHeader, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(channelId, request, requester, idempotencyKey));
    }

    /**
     * 비회원 주문 조회(gateway#323) — 로그인 없이 product.posselect.com 에서 부른다. 주문번호와 연락처가 맞으면 게스트
     * 토큰을 돌려주고, 화면은 그 토큰으로 상세·취소·반품을 이어 간다. 틀리면 이유를 구분하지 않고 404.
     */
    @PostMapping("/guest-lookup")
    public com.dh.order.dto.OrderDtos.GuestLookupResponse guestLookup(
            @jakarta.validation.Valid @RequestBody com.dh.order.dto.OrderDtos.GuestLookupRequest request) {
        return orderService.lookupGuestOrder(request.orderId(), request.phone());
    }

    // ------------------------------------------------------------------ 취소·반품 (gateway#320)

    /**
     * 고객 상품별 취소·반품 신청. 소유자는 게이트웨이가 넣은 신원 또는 게스트 토큰으로 판정하고, 아니면 404 다.
     * 출고 전 취소는 즉시 환불까지 끝나고(201 의 status=COMPLETED), 반품은 관리자 승인을 기다린다(REQUESTED).
     * {@code Idempotency-Key} 가 같으면 처음 만든 신청을 돌려준다.
     */
    @PostMapping("/{id}/claims")
    public ResponseEntity<com.dh.order.claim.ClaimDtos.ClaimResponse> createClaim(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody com.dh.order.claim.ClaimDtos.ClaimCreateRequest request,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        Requester requester = extractRequester(authHeader, guestToken);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.request(id, requester, request, blankToNull(idempotencyKey)));
    }

    /** 고객: 품목별 교환 가능 옵션(같은 상품·같은 가격·판매 중·재고 있음, gateway#320). 소유자 아니면 404. */
    @GetMapping("/{id}/exchange-options")
    public ResponseEntity<List<com.dh.order.claim.ClaimDtos.ExchangeOptions>> exchangeOptions(
            @PathVariable Long id,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ResponseEntity.ok(claimService.exchangeOptions(id, extractRequester(authHeader, guestToken)));
    }

    /** 관리자: 교환 운송장 등록 → 교환 완료(gateway#320). */
    @PostMapping("/claims/{claimId}/exchange-shipment")
    public ResponseEntity<com.dh.order.claim.ClaimDtos.ClaimResponse> shipExchange(
            @PathVariable Long claimId,
            @jakarta.validation.Valid @RequestBody com.dh.order.claim.ClaimDtos.ExchangeShipmentRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(claimService.shipExchange(claimId, request.carrier(), request.trackingNumber()));
    }

    /** 관리자: 상태별 신청 목록(기본은 처리 대기). */
    @GetMapping("/claims")
    public ResponseEntity<List<com.dh.order.claim.ClaimDtos.ClaimResponse>> listClaims(
            @RequestParam(value = "status", defaultValue = "REQUESTED") com.dh.order.claim.ClaimStatus status,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(claimService.listByStatus(status));
    }

    /** 관리자: 승인 — 환불과 재고 복원까지. 사유를 바꿀 수 있다(단순 변심 ↔ 하자·오배송). */
    @PostMapping("/claims/{claimId}/approve")
    public ResponseEntity<com.dh.order.claim.ClaimDtos.ClaimResponse> approveClaim(
            @PathVariable Long claimId,
            @RequestBody(required = false) com.dh.order.claim.ClaimDtos.ClaimApproveRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(claimService.approve(claimId, request == null ? null : request.reasonType()));
    }

    /** 관리자: 반려 — 잡아 둔 수량을 돌려놓는다. */
    @PostMapping("/claims/{claimId}/reject")
    public ResponseEntity<com.dh.order.claim.ClaimDtos.ClaimResponse> rejectClaim(
            @PathVariable Long claimId,
            @jakarta.validation.Valid @RequestBody com.dh.order.claim.ClaimDtos.ClaimRejectRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(claimService.reject(claimId, request.reason()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 로그인한 사용자 본인의 주문 목록.
    @GetMapping("/mine")
    public ResponseEntity<List<OrderSummaryResponse>> getMine(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            // 기간(한국 날짜, 양 끝 포함)과 페이지(gateway#321). 전부 선택 — 없으면 예전처럼 전체 목록.
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Requester requester = extractRequester(authHeader, null);
        if (requester.userId() == null && requester.userEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(orderService.getMyOrders(requester.userId(), requester.userEmail(), from, to, page, size));
    }

    /**
     * admin.front(Keycloak staff realm 로그인) 전용 주문 목록 — 페이지 단위 + 상태·기간·검색어(gateway#329).
     * Authorization 헤더의 토큰을 직접 검증한다. 검색어는 주문번호·주문자 이름·연락처·이메일에 건다.
     */
    @GetMapping
    public ResponseEntity<com.dh.order.dto.OrderDtos.AdminOrderPage> getAll(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "status", required = false) com.dh.order.domain.OrderStatus status,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String adminEmail = verifyAdmin(authHeader);
        if (adminEmail == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // 검색어는 이름·연락처일 수 있어 로그에 남기지 않는다.
        logger.info("admin order list viewed by {} (status={}, from={}, to={}, keyword={}, page={})",
                adminEmail, status, from, to, keyword == null || keyword.isBlank() ? "없음" : "있음", page);
        return ResponseEntity.ok(orderService.searchAdminOrders(status, from, to, keyword, page, size));
    }

    // 소유자(또는 게스트 토큰 보유자, 또는 admin)만 조회할 수 있다. 접근 불가면 404다 - 주문 ID가
    // 순번이라 403으로 구분해 주면 그것만으로 주문 건수와 유효 ID 범위가 노출된다(Redmine #214).
    @GetMapping("/{id}")
    public OrderResponse get(
            @PathVariable Long id,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return orderService.getOrder(id, extractRequester(authHeader, guestToken));
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(
            @PathVariable Long id,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return orderService.payOrder(id, extractRequester(authHeader, guestToken));
    }

    // admin.front(staff realm) 전용 - 운송장 등록 시 주문 상태가 PAID -> SHIPPED로 전이된다.
    @PostMapping("/{id}/shipment")
    public ResponseEntity<ShipmentResponse> createShipment(
            @PathVariable Long id,
            @Valid @RequestBody CreateShipmentRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ShipmentResponse response = orderService.createShipment(id, request);
        // 운송장 등록이 커밋된 뒤에 보낸다. 메일 실패는 서비스가 흡수하고 notification_failures 에 남긴다(#44).
        notificationService.notifyShipped(id, response.carrier(), response.trackingNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // admin.front 전용 - 배송완료 처리, 주문 상태가 SHIPPED -> DELIVERED로 전이된다.
    @PutMapping("/{id}/shipment/deliver")
    public ResponseEntity<ShipmentResponse> deliverShipment(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(orderService.markDelivered(id));
    }

    @GetMapping("/{id}/shipment")
    public ResponseEntity<ShipmentResponse> getShipment(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(orderService.getShipment(id));
    }

    // admin.front 전용 - 전액 환불 처리, 결제가 존재하는 주문 상태를 REFUNDED로 전이한다.
    @PostMapping("/{id}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable Long id,
            @RequestBody RefundRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (verifyAdmin(authHeader) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 1. 환불 상태 변경 + 재고 복원 예약(같은 트랜잭션)
        RefundResponse response = orderService.refundOrder(id, request);

        // 2. 재고 복원 원격 호출 (트랜잭션 밖). 실패해도 예약 행이 남아 스케줄러가 재시도한다(#34).
        inventoryCompensator.restoreRefundNow(id);

        // 3. 환불 안내 메일 (트랜잭션 밖, 실패해도 환불은 그대로)
        notificationService.notifyRefunded(id, response.amount(), response.reason());

        return ResponseEntity.ok(response);
    }

    private Requester extractRequester(String authHeader, String guestToken) {
        String adminEmail = verifyAdmin(authHeader);
        if (adminEmail != null) {
            return Requester.of(null, null, guestToken, true);
        }
        
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
                
        JWTClaimsSet claims = customerJwtVerifier.verify(token);
        if (claims != null) {
            try {
                return Requester.of(claims.getSubject(), claims.getStringClaim("email"), guestToken, false);
            } catch (Exception e) {
                // Ignore parse exception
            }
        }
        return Requester.of(null, null, guestToken, false);
    }

    /**
     * staff realm 토큰을 검증하고 <b>주문 관리 역할까지 확인한</b> 뒤 관리자 email 을 돌려준다.
     * 통과하지 못하면 null 이다.
     *
     * <p>이전에는 토큰이 유효하기만 하면 email 을 돌려줬다. 그래서 admin.front(lib/menu.ts)가
     * 주문 메뉴를 ORDER_MANAGER / SYSTEM_ADMIN 으로 제한해 두었는데도, PRODUCT_MANAGER 계정이
     * 자기 토큰으로 이 API 를 직접 호출하면 <b>수령인 이름·주소·연락처가 담긴 주문을 조회하고
     * 배송까지 등록</b>할 수 있었다(order.api#12). admin.front 미들웨어는 admin.posselect.com 을
     * 거칠 때만 도는 방어선이라 여기서 다시 막는다.
     *
     * <p>역할이 부족한 staff 토큰은 "관리자가 아님"으로 취급된다 — {@link #extractRequester}
     * 에서 관리자 권한을 얻지 못하고 일반 고객 토큰 검증으로 흘러간다.
     */
    private String verifyAdmin(String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        AdminPrincipal admin = adminJwtVerifier.verify(token);
        if (admin == null) {
            return null;
        }
        if (!admin.hasAnyRole(ORDER_MANAGER)) {
            logger.warn("관리자 주문 API 거부(역할 부족): {} roles={}", admin.email(), admin.roles());
            return null;
        }
        return admin.email();
    }
}
