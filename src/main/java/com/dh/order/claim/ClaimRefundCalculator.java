package com.dh.order.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 상품별 취소·반품 한 건의 환불액(gateway#320). 금액 규칙은 여기 한 곳에만 둔다 — 2026-10-14 결정:
 * <ol>
 *   <li>품목 금액(주문 당시 단가 × 수량)</li>
 *   <li>− 등급 할인 안분. "이번 클레임 뒤 남는 상품 금액에 해당하는 할인"을 원 미만 버림으로 구하고, 아직 돌려주지
 *       않은 할인과의 차이를 이번 몫으로 한다. 이렇게 하면 여러 번 나눠 돌려받아도 몫의 합이 원래 할인과 정확히 같다</li>
 *   <li>± 배송비. 고객 사유(취소·단순 변심)면 남은 상품으로 배송비를 다시 계산해 그 차이만큼 돌려주거나 뺀다
 *       (기준액 아래로 떨어지면 면제됐던 배송비를 뺀다). 판매자 사유(하자·오배송)면 빼지 않고, 마지막 상품까지
 *       돌려줄 때만 지금까지 낸 배송비를 돌려준다</li>
 *   <li>− 반품 배송비(편도). 단순 변심 반품에만</li>
 * </ol>
 * 결과는 0 아래로 내려가지 않고 남은 결제 금액을 넘지 않는다.
 */
public final class ClaimRefundCalculator {

    private ClaimRefundCalculator() {
    }

    public record Result(BigDecimal itemAmount, BigDecimal discountShare, BigDecimal shippingRefund,
            BigDecimal returnFee, BigDecimal refundAmount) {
    }

    /**
     * @param originalSubtotal 주문 당시 상품 합계(할인 전)
     * @param originalDiscount 주문 당시 등급 할인
     * @param priorDiscountShares 앞서 완료된 클레임이 가져간 할인 몫의 합
     * @param claimItemAmount 이번 클레임 품목 금액
     * @param remainingSubtotalAfter 이번 클레임까지 반영한 뒤 남는 상품 금액
     * @param shippingChargedBefore 지금까지 고객이 실제로 부담 중인 배송비(원래 배송비 − 앞선 배송비 환불 합)
     * @param shippingAfter 남는 상품만으로 다시 계산한 배송비(고객 사유일 때만 쓴다)
     * @param customerCaused 취소 또는 단순 변심 반품
     * @param returnShippingFee 단순 변심 반품의 반품 배송비(그 밖에는 0)
     * @param refundableLeft 결제 금액 − 앞서 환불한 합
     */
    public record Input(BigDecimal originalSubtotal, BigDecimal originalDiscount, BigDecimal priorDiscountShares,
            BigDecimal claimItemAmount, BigDecimal remainingSubtotalAfter, BigDecimal shippingChargedBefore,
            BigDecimal shippingAfter, boolean customerCaused, BigDecimal returnShippingFee, BigDecimal refundableLeft) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private BigDecimal originalSubtotal = BigDecimal.ZERO;
            private BigDecimal originalDiscount = BigDecimal.ZERO;
            private BigDecimal priorDiscountShares = BigDecimal.ZERO;
            private BigDecimal claimItemAmount = BigDecimal.ZERO;
            private BigDecimal remainingSubtotalAfter = BigDecimal.ZERO;
            private BigDecimal shippingChargedBefore = BigDecimal.ZERO;
            private BigDecimal shippingAfter = BigDecimal.ZERO;
            private boolean customerCaused;
            private BigDecimal returnShippingFee = BigDecimal.ZERO;
            private BigDecimal refundableLeft = BigDecimal.ZERO;

            public Builder originalSubtotal(BigDecimal v) { originalSubtotal = v; return this; }
            public Builder originalDiscount(BigDecimal v) { originalDiscount = v; return this; }
            public Builder priorDiscountShares(BigDecimal v) { priorDiscountShares = v; return this; }
            public Builder claimItemAmount(BigDecimal v) { claimItemAmount = v; return this; }
            public Builder remainingSubtotalAfter(BigDecimal v) { remainingSubtotalAfter = v; return this; }
            public Builder shippingChargedBefore(BigDecimal v) { shippingChargedBefore = v; return this; }
            public Builder shippingAfter(BigDecimal v) { shippingAfter = v; return this; }
            public Builder customerCaused(boolean v) { customerCaused = v; return this; }
            public Builder returnShippingFee(BigDecimal v) { returnShippingFee = v; return this; }
            public Builder refundableLeft(BigDecimal v) { refundableLeft = v; return this; }

            public Input build() {
                return new Input(originalSubtotal, originalDiscount, priorDiscountShares, claimItemAmount,
                        remainingSubtotalAfter, shippingChargedBefore, shippingAfter, customerCaused,
                        returnShippingFee, refundableLeft);
            }
        }
    }

    public static Result calculate(Input in) {
        BigDecimal remainingDiscount = in.originalSubtotal().signum() == 0
                ? BigDecimal.ZERO
                : in.originalDiscount().multiply(in.remainingSubtotalAfter())
                        .divide(in.originalSubtotal(), 0, RoundingMode.DOWN);
        BigDecimal discountShare = in.originalDiscount().subtract(in.priorDiscountShares()).subtract(remainingDiscount)
                .max(BigDecimal.ZERO);

        boolean nothingLeft = in.remainingSubtotalAfter().signum() == 0;
        BigDecimal shippingRefund;
        if (in.customerCaused()) {
            shippingRefund = in.shippingChargedBefore().subtract(nothingLeft ? BigDecimal.ZERO : in.shippingAfter());
        } else {
            shippingRefund = nothingLeft ? in.shippingChargedBefore() : BigDecimal.ZERO;
        }

        BigDecimal returnFee = in.returnShippingFee() == null ? BigDecimal.ZERO : in.returnShippingFee();
        BigDecimal refund = in.claimItemAmount().subtract(discountShare).add(shippingRefund).subtract(returnFee)
                .max(BigDecimal.ZERO)
                .min(in.refundableLeft().max(BigDecimal.ZERO));
        return new Result(in.claimItemAmount(), discountShare, shippingRefund, returnFee, refund);
    }
}
