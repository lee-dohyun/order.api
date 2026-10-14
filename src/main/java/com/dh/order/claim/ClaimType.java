package com.dh.order.claim;

/** 취소(출고 전) 또는 반품(수령 후). 교환은 다음 단계(gateway#320). */
public enum ClaimType {
    CANCEL,
    RETURN
}
