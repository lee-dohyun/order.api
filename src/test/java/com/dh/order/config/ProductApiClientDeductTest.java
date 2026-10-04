package com.dh.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.dh.order.domain.OrderItem;
import com.dh.order.service.OrderStateException;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;

/**
 * 재고 차감 호출의 실패 분류 (order.api#45).
 *
 * <p>재고 부족(product.api 409)은 고객이 수량을 줄이면 되는 정상적인 거절이고, 5xx·연결 실패는 장애다.
 * 둘이 섞이면 고객은 품절 상품에 "잠시 후 다시 시도"를 안내받고, 품절 시도 몇 번이 서킷브레이커를 열어
 * 정상 결제까지 막는다. 분류는 서킷브레이커 프록시와 폴백을 거쳐야 드러나므로 실제 프록시와 HTTP 로 본다.
 */
@SpringBootTest(classes = ProductApiClient.class)
@ImportAutoConfiguration({ JacksonAutoConfiguration.class, AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class })
class ProductApiClientDeductTest {

    private static HttpServer server;
    private static final AtomicInteger status = new AtomicInteger(200);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/inventory/deduct", exchange -> {
            byte[] body = "재고가 부족합니다: variantId=1, 요청=99999".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void baseUrl(DynamicPropertyRegistry registry) {
        registry.add("product-api.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Autowired private ProductApiClient client;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void resetBreaker() {
        breaker = circuitBreakerRegistry.circuitBreaker("productApi");
        breaker.reset();
    }

    @Test
    @DisplayName("product.api 409 는 재고 부족으로 응답하고, 반복돼도 서킷브레이커를 열지 않는다")
    void 재고_부족은_장애가_아니다() {
        status.set(409);

        // minimumNumberOfCalls(5)·failureRateThreshold(50%)를 넘기도록 충분히 부른다.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.deductInventory(1L, items()))
                    .isInstanceOf(OrderStateException.class)
                    .hasMessage("order.outOfStock");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("product.api 5xx 는 재고 확인 실패로 응답하고 서킷브레이커 실패로 센다")
    void 서버_오류는_장애다() {
        status.set(500);

        assertThatThrownBy(() -> client.deductInventory(1L, items()))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.inventoryUnavailable");

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private static List<OrderItem> items() {
        OrderItem item = new OrderItem();
        item.setVariantId(1L);
        item.setQuantity(99_999);
        return List.of(item);
    }
}
