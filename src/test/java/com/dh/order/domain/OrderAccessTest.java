package com.dh.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 주문 소유자 판정 규칙. Redmine posselect #214(비로그인 상태로 전 주문 열람 가능했던 취약점)의
 * 회귀 방지가 목적이라, "누가 볼 수 있는가"보다 "누가 못 보는가"를 더 촘촘히 검증한다.
 */
class OrderAccessTest {

    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ID = "22222222-2222-2222-2222-222222222222";

    @Nested
    @DisplayName("회원 주문 (customerId 있음)")
    class MemberOrder {

        private Order order() {
            Order order = new Order();
            order.setCustomerId(OWNER_ID);
            order.setCustomerEmail("owner@example.com");
            return order;
        }

        @Test
        void 소유자_본인은_접근한다() {
            assertThat(order().isAccessibleBy(OWNER_ID, "owner@example.com", null)).isTrue();
        }

        @Test
        void 다른_회원은_접근하지_못한다() {
            assertThat(order().isAccessibleBy(OTHER_ID, "other@example.com", null)).isFalse();
        }

        @Test
        void 비로그인_요청은_접근하지_못한다() {
            assertThat(order().isAccessibleBy(null, null, null)).isFalse();
        }

        /**
         * 소유자 계정이 있으면 이메일은 아예 보지 않는다. 이메일은 변경 가능하므로 이메일 일치를
         * 인가 근거로 인정하면 계정을 탈취하지 않고도 남의 주문에 접근할 여지가 생긴다.
         */
        @Test
        void 이메일만_같고_계정이_다르면_접근하지_못한다() {
            assertThat(order().isAccessibleBy(OTHER_ID, "owner@example.com", null)).isFalse();
        }

        @Test
        void 게스트_토큰으로는_회원_주문에_접근하지_못한다() {
            assertThat(order().isAccessibleBy(null, null, "아무-토큰")).isFalse();
        }
    }

    @Nested
    @DisplayName("레거시 회원 주문 (customerId 백필 전, customerEmail만 있음)")
    class LegacyMemberOrder {

        private Order order() {
            Order order = new Order();
            order.setCustomerEmail("legacy@example.com");
            return order;
        }

        @Test
        void 같은_이메일이면_접근한다() {
            assertThat(order().isAccessibleBy(OWNER_ID, "legacy@example.com", null)).isTrue();
        }

        @Test
        void 이메일_대소문자는_구분하지_않는다() {
            assertThat(order().isAccessibleBy(OWNER_ID, "Legacy@Example.com", null)).isTrue();
        }

        @Test
        void 다른_이메일은_접근하지_못한다() {
            assertThat(order().isAccessibleBy(OWNER_ID, "someone@example.com", null)).isFalse();
        }

        @Test
        void 비로그인_요청은_접근하지_못한다() {
            assertThat(order().isAccessibleBy(null, null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("게스트 주문 (소유자 계정 없음)")
    class GuestOrder {

        private Order order() {
            Order order = new Order();
            order.setGuestToken("guest-token-abc");
            return order;
        }

        @Test
        void 발급받은_토큰을_제시하면_접근한다() {
            assertThat(order().isAccessibleBy(null, null, "guest-token-abc")).isTrue();
        }

        /** #214의 본체 — 소유자가 없다고 해서 통과시키면 안 된다. */
        @Test
        void 토큰_없이는_접근하지_못한다() {
            assertThat(order().isAccessibleBy(null, null, null)).isFalse();
        }

        @Test
        void 틀린_토큰은_접근하지_못한다() {
            assertThat(order().isAccessibleBy(null, null, "guest-token-xyz")).isFalse();
        }

        @Test
        void 로그인만_했다고_남의_게스트_주문에_접근하지_못한다() {
            assertThat(order().isAccessibleBy(OTHER_ID, "other@example.com", null)).isFalse();
        }

        /** 토큰이 아직 없는 주문에 null을 제시해 통과하는 null == null 함정 방지. */
        @Test
        void 토큰이_없는_주문에_null을_제시해도_접근하지_못한다() {
            assertThat(new Order().isAccessibleBy(null, null, null)).isFalse();
        }
    }
}
