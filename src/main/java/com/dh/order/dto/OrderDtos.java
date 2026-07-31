package com.dh.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class OrderDtos {

    // order-api는 catalogdb에 접근하지 않으므로, 주문 시점의 상품명/가격은 클라이언트(카트)가 스냅샷으로 함께 보냄
    public record OrderItemRequest(
            @NotNull Long productId,
            @NotBlank String productName,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(1) Integer quantity) {
    }

    public record OrderCreateRequest(
            @NotBlank String ordererName,
            @NotBlank String ordererPhone,
            @NotBlank String shippingAddress,
            @NotEmpty @Valid List<OrderItemRequest> items) {
    }

    public record OrderItemResponse(Long productId, String productName, BigDecimal price, Integer quantity) {
    }

    public record OrderResponse(
            Long id,
            String ordererName,
            String ordererPhone,
            String shippingAddress,
            String status,
            BigDecimal totalPrice,
            List<OrderItemResponse> items,
            LocalDateTime createdAt,
            LocalDateTime paidAt) {
    }

    public record OrderSummaryResponse(
            Long id,
            String status,
            BigDecimal totalPrice,
            int itemCount,
            LocalDateTime createdAt) {
    }

    public record OrderAdminSummaryResponse(
            Long id,
            String customerEmail,
            String ordererName,
            String ordererPhone,
            String status,
            BigDecimal totalPrice,
            int itemCount,
            LocalDateTime createdAt) {
    }
}
