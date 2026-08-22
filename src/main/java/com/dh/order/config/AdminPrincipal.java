package com.dh.order.config;

import java.util.Set;

/**
 * staff realm JWT 에서 뽑아낸 관리자 신원.
 *
 * <p>이전에는 {@code AdminJwtVerifier} 가 email 문자열만 돌려줬고 호출부는 그 값이
 * null 이 아닌지만 봤다. 그래서 <b>staff realm 에 로그인만 되면 역할과 무관하게 관리자
 * 주문 API 가 열렸다</b>(order.api#12). 주문에는 수령인 이름·주소·연락처가 들어 있다.
 *
 * <p>product.api 에도 같은 타입이 있다. 이 프로젝트는 공유 라이브러리 없이 서비스별로
 * 자기 완결적으로 두는 관례를 따르므로 의도적인 중복이다 — 한쪽만 고치지 말 것.
 */
public record AdminPrincipal(String email, Set<String> roles) {

    /** 모든 관리 기능을 쓸 수 있는 상위 역할. 개별 역할 검사에서 항상 통과한다. */
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    public boolean hasAnyRole(String... required) {
        if (roles.contains(SYSTEM_ADMIN)) {
            return true;
        }
        for (String role : required) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
