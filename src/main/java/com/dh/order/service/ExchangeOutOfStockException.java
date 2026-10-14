package com.dh.order.service;

/**
 * 교환할 옵션의 재고 부족(gateway#320). product.api 의 정상 판정이라 서킷브레이커 실패로 세지 않는다
 * ({@code application.yml} 의 ignoreExceptions) — {@link OutOfStockException} 과 같은 이유다(order.api#45).
 */
public class ExchangeOutOfStockException extends OrderStateException {

    public ExchangeOutOfStockException() {
        super("order.exchangeOutOfStock");
    }
}
