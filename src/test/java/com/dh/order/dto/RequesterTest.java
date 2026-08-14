package com.dh.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dh.order.dto.OrderDtos.Requester;

class RequesterTest {

    /**
     * 게이트웨이는 클레임이 없으면 헤더를 빈 문자열로 채운다(safeString). 빈 문자열이 그대로
     * 소유자 키가 되면 클레임 없는 토큰들끼리 서로의 주문에 접근하게 되므로 null로 정규화한다.
     */
    @Test
    void 빈_문자열_헤더는_null로_정규화된다() {
        Requester requester = Requester.of("", "  ", "", false);

        assertThat(requester.userId()).isNull();
        assertThat(requester.userEmail()).isNull();
        assertThat(requester.guestToken()).isNull();
    }

    @Test
    void 값이_있으면_그대로_보존한다() {
        Requester requester = Requester.of("sub-1", "a@b.com", "tok", true);

        assertThat(requester.userId()).isEqualTo("sub-1");
        assertThat(requester.userEmail()).isEqualTo("a@b.com");
        assertThat(requester.guestToken()).isEqualTo("tok");
        assertThat(requester.admin()).isTrue();
    }
}
