package com.dh.order.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dh.order.config.AdminJwtVerifier;
import com.dh.order.config.AdminPrincipal;
import com.dh.order.config.CustomerJwtVerifier;
import com.dh.order.config.Messages;
import com.dh.order.config.ProductApiClient;
import com.dh.order.service.OrderService;

/**
 * order.api#12 회귀 방지.
 *
 * <p>관리자 주문 목록은 수령인 이름·주소·연락처가 담긴 개인정보다. 이 테스트가 없던 동안
 * 백엔드는 "staff realm 토큰이 유효한가"만 보고 통과시켰고, admin.front 가 화면에서 걸어 둔
 * ORDER_MANAGER 제한은 admin.posselect.com 을 거치지 않으면 그냥 없는 것과 같았다.
 */
@WebMvcTest(OrderController.class)
class OrderControllerAdminRoleTest {

    private static final String BEARER = "Bearer token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private AdminJwtVerifier adminJwtVerifier;
    @MockitoBean
    private CustomerJwtVerifier customerJwtVerifier;
    @MockitoBean
    private ProductApiClient productApiClient;
    // @RestControllerAdvice(ApiExceptionHandler)가 @WebMvcTest 슬라이스에 함께 올라오므로 필요하다
    @MockitoBean
    private Messages messages;

    private void givenStaffToken(String... roles) {
        given(adminJwtVerifier.verify("token"))
                .willReturn(new AdminPrincipal("staff@posselect.com", Set.of(roles)));
    }

    @Test
    @DisplayName("ORDER_MANAGER 는 관리자 주문 목록을 조회할 수 있다")
    void orderManagerCanList() throws Exception {
        givenStaffToken("ORDER_MANAGER");
        given(orderService.getAllOrders()).willReturn(List.of());

        mockMvc.perform(get("/api/orders").header("Authorization", BEARER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN 은 개별 역할이 없어도 조회할 수 있다")
    void systemAdminCanList() throws Exception {
        givenStaffToken("SYSTEM_ADMIN");
        given(orderService.getAllOrders()).willReturn(List.of());

        mockMvc.perform(get("/api/orders").header("Authorization", BEARER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRODUCT_MANAGER 는 토큰이 유효해도 주문 목록이 거부된다 — 이것이 order.api#12 의 결함이었다")
    void productManagerIsRejected() throws Exception {
        givenStaffToken("PRODUCT_MANAGER");

        mockMvc.perform(get("/api/orders").header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("역할이 하나도 없는 staff 토큰도 거부된다")
    void noRolesIsRejected() throws Exception {
        givenStaffToken();

        mockMvc.perform(get("/api/orders").header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PRODUCT_MANAGER 는 운송장 조회도 거부된다 (다른 관리자 엔드포인트에도 같은 게이트가 걸린다)")
    void productManagerCannotReadShipment() throws Exception {
        givenStaffToken("PRODUCT_MANAGER");

        mockMvc.perform(get("/api/orders/1/shipment").header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰이 없으면 거부된다")
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isForbidden());
    }
}
