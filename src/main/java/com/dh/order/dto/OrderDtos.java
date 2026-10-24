package com.dh.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class OrderDtos {

    /**
     * 품목은 {@code offerId} 또는 {@code variantId} 중 <b>정확히 하나</b>와 수량만 받는다(order.api#14).
     * 상품명·가격·productId·판매자는 서버가 product.api 에서 확정하므로 요청 본문에 있어도 무시된다 —
     * 예전엔 price 를 그대로 믿어서 임의 금액 주문이 가능했다(#232). 기존 프론트가 아직 그 필드들을
     * 함께 보내지만 Jackson 이 조용히 버린다.
     *
     * <p>{@code variantId} 경로는 호환용이다. 장바구니·주문을 만드는 product.front 가 아직
     * variantId 만 알고 있어서, 그 경우 product.api 가 SKU 의 대표 오퍼를 골라 확정한다(product.api#69).
     */
    public record OrderItemRequest(
            Long offerId,
            Long variantId,
            @NotNull @Min(1) Integer quantity) {

        /** 둘 다 주면 어느 오퍼로 샀는지 모호하고, 둘 다 없으면 무엇을 샀는지 알 수 없다 — 400. */
        @AssertTrue(message = "offerId 와 variantId 중 정확히 하나를 지정해야 한다")
        public boolean isExactlyOneTarget() {
            return (offerId == null) != (variantId == null);
        }
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
            @NotEmpty @Valid List<OrderItemRequest> items,
            /** 비회원 주문의 연락 이메일(gateway#323). 비회원은 필수, 회원은 무시한다(계정 이메일로 보낸다). */
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 200) String guestEmail) {

        public OrderCreateRequest(String ordererName, String ordererPhone, String shippingAddress, String recipientName,
                String recipientPhone, String zipCode, String address1, String address2, List<OrderItemRequest> items) {
            this(ordererName, ordererPhone, shippingAddress, recipientName, recipientPhone, zipCode, address1, address2,
                    items, null);
        }
    }

    /** 비회원 주문 조회(gateway#323). 주문번호와 주문할 때 적은 연락처가 맞으면 게스트 토큰을 돌려준다. */
    public record GuestLookupRequest(@NotNull Long orderId, @NotBlank String phone) {
    }

    public record GuestLookupResponse(Long orderId, String guestToken) {
    }

    /**
     * 주문 상품 한 줄.
     *
     * <p>{@code sellerId}/{@code sellerName} 은 주문 시점 스냅샷이다. 1P 인 지금은 항상 자사지만,
     * 3P 로 넘어가면 <b>전자상거래법상 판매자 신원을 청약 전에 제공할 의무</b>가 생기므로
     * 고객 응답에도 실려야 한다(order.api#15). 지금부터 실어 두면 그때 프론트 계약이 안 바뀐다.
     */
    /** {@code orderItemId}·{@code claimedQuantity} 는 취소·반품 신청에 쓴다(gateway#320). */
    public record OrderItemResponse(
            Long productId, Long variantId, String productName, BigDecimal price, Integer quantity,
            Long sellerId, String sellerName, Long offerId, Long orderItemId, Integer claimedQuantity) {
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

    /**
     * guestToken은 주문 생성 응답에서만 채워진다 - 조회 응답에서는 항상 null이다.
     *
     * <p>{@code totalPrice} 는 결제 금액(상품 합계 - 등급 할인 + 배송비)이고 {@code subtotalPrice} 는 할인 전
     * 상품 합계다. {@code shippingFee} 는 주문 시점에 청구한 배송비다(gateway#322). {@code gradeCode}/{@code gradeDiscountRate} 는 할인이 적용된 주문에만 채워진다(gateway#82).
     */
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
            BigDecimal subtotalPrice,
            BigDecimal discountAmount,
            BigDecimal shippingFee,
            /** 택배사·운송장·배송 상태(gateway#321). 운송장 등록 전에는 null. */
            ShipmentResponse shipment,
            String gradeCode,
            BigDecimal gradeDiscountRate,
            List<OrderItemResponse> items,
            LocalDateTime createdAt,
            LocalDateTime paidAt,
            String guestToken,
            List<com.dh.order.claim.ClaimDtos.ClaimResponse> claims,
            /** 주문 전체 환불 내역(gateway#329). 환불 전에는 null. 상품별 취소·반품 환불은 claims 에 있다. */
            RefundResponse refund) {
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

    /**
     * 관리자 주문 목록 한 페이지(gateway#329). {@code page} 는 0부터 센다. {@code totalElements} 는 검색 조건에 걸린
     * 전체 건수다.
     */
    public record AdminOrderPage(
            List<OrderAdminSummaryResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
