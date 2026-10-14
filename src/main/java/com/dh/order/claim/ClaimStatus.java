package com.dh.order.claim;

/** 취소는 신청 즉시 COMPLETED, 반품은 REQUESTED → 관리자 승인(COMPLETED)·반려(REJECTED). */
public enum ClaimStatus {
    REQUESTED,
    /** 교환 승인됨 — 재고 처리는 끝났고 교환 운송장 등록을 기다린다(교환에만). */
    APPROVED,
    COMPLETED,
    REJECTED
}
