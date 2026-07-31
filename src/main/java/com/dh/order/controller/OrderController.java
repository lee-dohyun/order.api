package com.dh.order.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.order.dto.OrderDtos.OrderAdminSummaryResponse;
import com.dh.order.dto.OrderDtos.OrderCreateRequest;
import com.dh.order.dto.OrderDtos.OrderResponse;
import com.dh.order.dto.OrderDtos.OrderSummaryResponse;
import com.dh.order.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final String adminSecret;

    public OrderController(OrderService orderService, @Value("${admin.shared-secret:}") String adminSecret) {
        this.orderService = orderService;
        this.adminSecret = adminSecret;
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

    // admin.front 전용 전체 주문 목록. admin.front와만 공유하는 비밀값을 헤더로 요구한다.
    @GetMapping
    public ResponseEntity<List<OrderAdminSummaryResponse>> getAll(
            @RequestHeader(value = "X-Admin-Secret", required = false) String providedSecret) {
        if (adminSecret.isBlank() || providedSecret == null || !adminSecret.equals(providedSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
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
}
