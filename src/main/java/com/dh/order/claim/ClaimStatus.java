package com.dh.order.claim;

/** 취소는 신청 즉시 COMPLETED, 반품은 REQUESTED → 관리자 승인(COMPLETED)·반려(REJECTED). */
public enum ClaimStatus {
    REQUESTED,
    COMPLETED,
    REJECTED
}
