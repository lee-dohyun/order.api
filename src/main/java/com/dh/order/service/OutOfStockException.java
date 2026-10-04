package com.dh.order.service;

/**
 * 재고가 부족해 결제를 확정할 수 없다 — product.api 가 재고 차감을 409 로 거절한 경우다(order.api#45).
 *
 * <p>{@link OrderStateException} 과 타입을 나눈 이유는 서킷브레이커다. 재고 부족은 product.api 가 정상적으로
 * 내린 판정이지 장애가 아니므로 실패로 세면 안 되고(application.yml {@code ignoreExceptions}),
 * 폴백이 "재고 확인 실패"로 바꿔 던져도 안 된다. 메시지 키로는 그 둘을 설정에서 구분할 수 없다.
 */
public class OutOfStockException extends OrderStateException {

    public OutOfStockException() {
        super("order.outOfStock");
    }
}
