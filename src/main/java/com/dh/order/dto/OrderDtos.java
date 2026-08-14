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

    // order-api는 catalogdb에 접근하지 않으므로, 주문 시점의 상품명/가격은 클라이언트(카트)가 스냅샷으로
    // 함께 보냄. variantId는 결제 확정 시 product.api 재고 차감의 기준 키.
    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull Long variantId,
            @NotBlank String productName,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(1) Integer quantity) {
    }

    public record OrderCreateRequest(
            @NotBlank String ordererName,
            @NotBlank String ordererPhone,
            @NotBlank String shippingAddress,
            String recipientName,
            String recipientPhone,
            String zipCode,
            String address1,
            String address2,
            @NotEmpty @Valid List<OrderItemRequest> items) {
    }

    public record OrderItemResponse(
            Long productId, Long variantId, String productName, BigDecimal price, Integer quantity) {
    }

    /**
     * 요청자 신원. userId/userEmail은 게이트웨이가 JWT 검증 후 주입한 헤더에서만 오고,
     * guestToken은 주문 생성 응답을 받은 클라이언트가 되돌려 보낸다. admin은 staff realm
     * 토큰 재검증 결과다.
     */
    public record Requester(String userId, String userEmail, String guestToken, boolean admin) {

        public static Requester of(String userId, String userEmail, String guestToken, boolean admin) {
            return new Requester(blankToNull(userId), blankToNull(userEmail), blankToNull(guestToken), admin);
        }

        // 게이트웨이는 클레임이 없을 때 헤더를 빈 문자열로 채운다 - ""가 소유자 키로 취급되면
        // 클레임 없는 토큰끼리 서로의 주문에 접근할 수 있으므로 null로 정규화한다.
        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    /** guestToken은 주문 생성 응답에서만 채워진다 - 조회 응답에서는 항상 null이다. */
    public record OrderResponse(
            Long id,
            String ordererName,
            String ordererPhone,
            String shippingAddress,
            String recipientName,
            String recipientPhone,
            String zipCode,
            String address1,
            String address2,
            String status,
            BigDecimal totalPrice,
            List<OrderItemResponse> items,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            String guestToken) {
    }

    public record CreateShipmentRequest(
            @NotBlank String carrier,
            @NotBlank String trackingNumber) {
    }

    public record ShipmentResponse(
            Long orderId,
            String carrier,
            String trackingNumber,
            String status,
            LocalDateTime shippedAt,
            LocalDateTime deliveredAt) {
    }

    public record RefundRequest(String reason) {
    }

    public record RefundResponse(
            Long orderId,
            BigDecimal amount,
            String reason,
            String status,
            LocalDateTime refundedAt) {
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
