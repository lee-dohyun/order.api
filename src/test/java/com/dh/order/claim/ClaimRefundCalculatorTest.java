package com.dh.order.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dh.order.claim.ClaimRefundCalculator.Input;
import com.dh.order.claim.ClaimRefundCalculator.Result;

/**
 * 상품별 취소·반품의 환불액(gateway#320, 2026-10-14 결정): 품목 금액 − 등급 할인 안분, 고객 사유(취소·단순 변심)면
 * 배송비 재계산, 단순 변심 반품이면 반품 배송비 차감. 여러 번 나눠 돌려받아도 합계가 결제 금액과 맞아야 한다.
 */
class ClaimRefundCalculatorTest {

    private static BigDecimal won(long v) {
        return BigDecimal.valueOf(v);
    }

    /** 상품 A 30,000 + B 20,000 = 50,000, 등급 할인 2% = 1,000, 배송비 0(5만원 이상 무료) → 결제 49,000. */
    private static Input.Builder order() {
        return Input.builder()
                .originalSubtotal(won(50_000)).originalDiscount(won(1_000))
                .priorDiscountShares(won(0)).shippingChargedBefore(won(0))
                .refundableLeft(won(49_000));
    }

    @Test
    @DisplayName("주문 전체를 취소하면 결제 금액 전부를 돌려준다")
    void fullCancel() {
        Result r = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(50_000)).remainingSubtotalAfter(won(0))
                .customerCaused(true).shippingAfter(won(0)).build());

        assertThat(r.discountShare()).isEqualByComparingTo("1000");
        assertThat(r.refundAmount()).isEqualByComparingTo("49000");
    }

    @Test
    @DisplayName("등급 할인은 상품 금액 비율로 나눈다 — 20,000 반품이면 할인 400 을 뺀다")
    void discountIsProrated() {
        Result r = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(30_000))
                .customerCaused(false).shippingAfter(won(0)).build());

        assertThat(r.discountShare()).isEqualByComparingTo("400");
        assertThat(r.refundAmount()).isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("고객 사유로 남은 상품이 무료배송 기준 아래로 떨어지면 면제됐던 배송비를 뺀다")
    void shippingRecalculatedWhenThresholdLost() {
        Result r = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(30_000))
                .customerCaused(true).shippingAfter(won(3_000)).build());

        assertThat(r.shippingRefund()).isEqualByComparingTo("-3000");
        assertThat(r.refundAmount()).isEqualByComparingTo("16600");
    }

    @Test
    @DisplayName("상품 하자면 기준액이 깨져도 배송비를 빼지 않는다")
    void defectiveDoesNotDeductShipping() {
        Result r = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(30_000))
                .customerCaused(false).shippingAfter(won(3_000)).build());

        assertThat(r.shippingRefund()).isEqualByComparingTo("0");
        assertThat(r.refundAmount()).isEqualByComparingTo("19600");
    }

    @Test
    @DisplayName("단순 변심 반품은 반품 배송비(편도)를 뺀다")
    void returnFeeForChangeOfMind() {
        Result r = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(30_000))
                .customerCaused(true).shippingAfter(won(0)).returnShippingFee(won(2_500)).build());

        assertThat(r.returnFee()).isEqualByComparingTo("2500");
        assertThat(r.refundAmount()).isEqualByComparingTo("17100");
    }

    @Test
    @DisplayName("마지막 상품까지 돌려주면 지금까지 낸 배송비를 돌려준다(하자 사유)")
    void lastItemRefundsShippingWhenDefective() {
        Result r = ClaimRefundCalculator.calculate(Input.builder()
                .originalSubtotal(won(20_000)).originalDiscount(won(0)).priorDiscountShares(won(0))
                .shippingChargedBefore(won(3_000)).refundableLeft(won(23_000))
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(0))
                .customerCaused(false).shippingAfter(won(0)).build());

        assertThat(r.shippingRefund()).isEqualByComparingTo("3000");
        assertThat(r.refundAmount()).isEqualByComparingTo("23000");
    }

    @Test
    @DisplayName("나눠서 돌려받아도 할인 안분 합계가 원래 할인과 정확히 같다(원 미만 버림이 쌓이지 않는다)")
    void sequentialSharesSumExactly() {
        // 10,001 × 3 = 30,003, 할인 3% = 900. 한 개씩 세 번 반품.
        BigDecimal subtotal = won(30_003);
        BigDecimal discount = won(900);
        BigDecimal prior = won(0);
        BigDecimal refunded = won(0);
        BigDecimal paid = subtotal.subtract(discount);
        for (int left = 2; left >= 0; left--) {
            Result r = ClaimRefundCalculator.calculate(Input.builder()
                    .originalSubtotal(subtotal).originalDiscount(discount).priorDiscountShares(prior)
                    .shippingChargedBefore(won(0)).refundableLeft(paid.subtract(refunded))
                    .claimItemAmount(won(10_001)).remainingSubtotalAfter(won(10_001L * left))
                    .customerCaused(true).shippingAfter(won(0)).build());
            prior = prior.add(r.discountShare());
            refunded = refunded.add(r.refundAmount());
        }
        assertThat(prior).isEqualByComparingTo("900");
        assertThat(refunded).isEqualByComparingTo(paid);
    }

    @Test
    @DisplayName("환불액은 0 아래로 내려가지 않고, 남은 결제 금액을 넘지 않는다")
    void clamped() {
        Result low = ClaimRefundCalculator.calculate(order()
                .claimItemAmount(won(1_000)).remainingSubtotalAfter(won(49_000))
                .customerCaused(true).shippingAfter(won(3_000)).returnShippingFee(won(2_500)).build());
        assertThat(low.refundAmount()).isEqualByComparingTo("0");

        Result high = ClaimRefundCalculator.calculate(order().refundableLeft(won(100))
                .claimItemAmount(won(20_000)).remainingSubtotalAfter(won(30_000))
                .customerCaused(false).shippingAfter(won(0)).build());
        assertThat(high.refundAmount()).isEqualByComparingTo("100");
    }
}
