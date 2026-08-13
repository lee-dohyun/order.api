package com.dh.order.service;

/**
 * 고객에게 그대로 보여줄 주문 상태 오류. 완성된 문구 대신 메시지 키만 들고 나가고
 * 실제 문구는 ApiExceptionHandler가 요청 로케일로 해석한다.
 *
 * <p>IllegalStateException을 상속하는 건 기존 409 매핑을 그대로 타기 위해서다.
 * 운영자만 보는 오류(환불 불가 상태 등)는 굳이 이걸 쓰지 않고 평범한
 * IllegalStateException으로 남겨둔다 — admin.front는 한국어 전용 내부 도구다.
 */
public class OrderStateException extends IllegalStateException {

    private final String messageKey;
    private final transient Object[] messageArgs;

    public OrderStateException(String messageKey, Object... messageArgs) {
        super(messageKey);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getMessageArgs() {
        return messageArgs;
    }
}
