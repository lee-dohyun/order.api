package com.dh.order.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.order.config.AdminJwtVerifier;
import com.dh.order.dto.OrderDtos.CreateShipmentRequest;
import com.dh.order.dto.OrderDtos.OrderAdminSummaryResponse;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.dto.OrderDtos.RefundRequest;
import com.dh.order.dto.OrderDtos.RefundResponse;
import com.dh.order.dto.OrderDtos.Requester;
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.service.OrderService;
import com.dh.order.config.ProductApiClient;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    /** 게이트웨이가 JWT 검증 후에만 주입하는 신원 헤더. 클라이언트가 보낸 동명 헤더는 게이트웨이가 먼저 제거한다. */
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    /** 게스트 주문 접근 토큰. 주문 생성 응답으로 받은 값을 클라이언트가 되돌려 보낸다. */
    private static final String GUEST_TOKEN_HEADER = "X-Order-Guest-Token";

    private final OrderService orderService;
    private final AdminJwtVerifier adminJwtVerifier;
    private final ProductApiClient productApiClient;

    public OrderController(OrderService orderService, AdminJwtVerifier adminJwtVerifier, ProductApiClient productApiClient) {
        this.orderService = orderService;
        this.adminJwtVerifier = adminJwtVerifier;
        this.productApiClient = productApiClient;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = "X-Channel", defaultValue = "1") Long channelId,
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail) {
        Requester requester = Requester.of(userId, userEmail, null, false);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(channelId, request, requester));
    }

    // 로그인한 사용자 본인의 주문 목록. 게이트웨이가 로그인 시에만 신원 헤더를 넣어주므로,
    // 비로그인 상태로 호출되면 헤더가 없어 401을 응답한다.
    @GetMapping("/mine")
    public ResponseEntity<List<OrderSummaryResponse>> getMine(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail) {
        Requester requester = Requester.of(userId, userEmail, null, false);
        if (requester.userId() == null && requester.userEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(orderService.getMyOrders(requester.userId(), requester.userEmail()));
    }

    // admin.front(Keycloak staff realm 로그인) 전용 전체 주문 목록. Authorization 헤더의 토큰을 직접 검증한다.
    @GetMapping
    public ResponseEntity<List<OrderAdminSummaryResponse>> getAll(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String adminEmail = verifyAdmin(authHeader);
        if (adminEmail == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        logger.info("admin order list viewed by {}", adminEmail);
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    // 소유자(또는 게스트 토큰 보유자, 또는 admin)만 조회할 수 있다. 접근 불가면 404다 - 주문 ID가
    // 순번이라 403으로 구분해 주면 그것만으로 주문 건수와 유효 ID 범위가 노출된다(Redmine #214).
    @GetMapping("/{id}")
    public OrderResponse get(
            @PathVariable Long id,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return orderService.getOrder(id, requesterOf(userId, userEmail, guestToken, authHeader));
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(
            @PathVariable Long id,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail,
            @RequestHeader(value = GUEST_TOKEN_HEADER, required = false) String guestToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return orderService.payOrder(id, requesterOf(userId, userEmail, guestToken, authHeader));
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
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createShipment(id, request));
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

        // 1. 환불할 주문의 상품 목록(아이템) 조회
        OrderResponse orderResponse = orderService.getOrder(id, requesterOf(null, null, null, authHeader));

        // 2. 내부 트랜잭션으로 환불 상태 변경
        RefundResponse response = orderService.refundOrder(id, request);

        // 3. 재고 복원 원격 호출 (트랜잭션 밖에서 실행하여 롤백 방지)
        try {
            productApiClient.restoreInventory(id, orderResponse.items());
        } catch (Exception e) {
            logger.error("환불은 성공했으나 재고 복원 호출 실패 - 수동 복원 필요. orderId={}", id, e);
        }

        return ResponseEntity.ok(response);
    }

    private Requester requesterOf(String userId, String userEmail, String guestToken, String authHeader) {
        return Requester.of(userId, userEmail, guestToken, verifyAdmin(authHeader) != null);
    }

    private String verifyAdmin(String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        return adminJwtVerifier.verify(token);
    }
}
