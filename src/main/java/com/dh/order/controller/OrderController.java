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
import com.dh.order.dto.OrderDtos.ShipmentResponse;
import com.dh.order.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final AdminJwtVerifier adminJwtVerifier;

    public OrderController(OrderService orderService, AdminJwtVerifier adminJwtVerifier) {
        this.orderService = orderService;
        this.adminJwtVerifier = adminJwtVerifier;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "X-User-Email", required = false) String customerEmail) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, customerEmail));
    }

    // 로그인한 사용자 본인의 주문 목록. 게이트웨이가 로그인 시에만 X-User-Email을 넣어주므로,
    // 비로그인 상태로 호출되면 헤더가 없어 401을 응답한다.
    @GetMapping("/mine")
    public ResponseEntity<List<OrderSummaryResponse>> getMine(
            @RequestHeader(value = "X-User-Email", required = false) String customerEmail) {
        if (customerEmail == null || customerEmail.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(orderService.getMyOrders(customerEmail));
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

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(@PathVariable Long id) {
        return orderService.payOrder(id);
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

    private String verifyAdmin(String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        return adminJwtVerifier.verify(token);
    }
}
