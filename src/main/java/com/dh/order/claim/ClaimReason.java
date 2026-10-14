package com.dh.order.claim;

/** 단순 변심은 고객 사유(배송비 재계산·반품 배송비 차감), 하자·오배송은 판매자 사유. */
public enum ClaimReason {
    CHANGE_OF_MIND,
    DEFECTIVE,
    WRONG_ITEM
}
