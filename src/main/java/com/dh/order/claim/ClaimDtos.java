package com.dh.order.claim;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 고객 상품별 취소·반품(gateway#320) 요청·응답. */
public class ClaimDtos {

    public record ClaimItemRequest(@NotNull Long orderItemId, @NotNull @Min(1) Integer quantity) {
    }

    /** 취소는 사유를 고르지 않는다(언제나 고객 사유). 반품은 사유가 필수다. */
    public record ClaimCreateRequest(
            @NotNull ClaimType type,
            ClaimReason reasonType,
            @Size(max = 500) String reasonDetail,
            @NotEmpty @Valid List<ClaimItemRequest> items) {
    }

    /** 관리자 승인 — 사유를 바꿀 수 있다(바꾸지 않으면 고객이 고른 사유). */
    public record ClaimApproveRequest(ClaimReason reasonType) {
    }

    public record ClaimRejectRequest(@NotNull @Size(min = 1, max = 300) String reason) {
    }

    public record ClaimItemResponse(Long orderItemId, String productName, Integer quantity) {
    }

    public record ClaimResponse(
            Long id,
            Long orderId,
            String type,
            String status,
            String reasonType,
            String reasonDetail,
            List<ClaimItemResponse> items,
            BigDecimal itemAmount,
            BigDecimal discountShare,
            BigDecimal shippingRefund,
            BigDecimal returnShippingFee,
            BigDecimal refundAmount,
            String rejectReason,
            LocalDateTime createdAt,
            LocalDateTime decidedAt) {

        public static ClaimResponse of(OrderClaim c) {
            return new ClaimResponse(c.getId(), c.getOrder().getId(), c.getType().name(), c.getStatus().name(),
                    c.getReasonType().name(), c.getReasonDetail(),
                    c.getItems().stream()
                            .map(i -> new ClaimItemResponse(i.getOrderItem().getId(), i.getOrderItem().getProductName(),
                                    i.getQuantity()))
                            .toList(),
                    c.getItemAmount(), c.getDiscountShare(), c.getShippingRefund(), c.getReturnShippingFee(),
                    c.getRefundAmount(), c.getRejectReason(), c.getCreatedAt(), c.getDecidedAt());
        }
    }
}
