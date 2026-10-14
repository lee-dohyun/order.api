package com.dh.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.dh.order.config.ProductApiClient.ShippingQuoteItem;
import com.dh.order.service.OrderStateException;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;

/**
 * 배송비 견적 호출(gateway#322). 받은 금액이 그대로 결제 금액에 더해지므로, 금액을 확인하지 못한 모든
 * 경우는 0 원으로 넘어가지 않고 주문 생성을 실패시켜야 한다. 서킷브레이커 프록시와 폴백을 거친 결과를
 * 봐야 해서 실제 프록시와 HTTP 로 본다({@link ProductApiClientDeductTest} 와 같은 구성).
 */
@SpringBootTest(classes = ProductApiClient.class)
@ImportAutoConfiguration({ Jackson2AutoConfiguration.class, AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class })
class ProductApiClientShippingQuoteTest {

    private static HttpServer server;
    private static final AtomicInteger status = new AtomicInteger(200);
    private static final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private static final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/offers/shipping-quote", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    @BeforeEach
    void reset() {
        circuitBreakerRegistry.circuitBreaker("productApi").reset();
        status.set(200);
        requestBody.set(null);
    }

    @Test
    @DisplayName("오퍼 id 와 수량만 보내고, 응답의 배송비를 그대로 돌려준다")
    void returnsQuotedFee() {
        responseBody.set("{\"shippingFee\":3000.00}");

        assertThat(client.quoteShipping(List.of(new ShippingQuoteItem(501L, 2))).shippingFee()).isEqualByComparingTo("3000");
        assertThat(requestBody.get()).isEqualTo("{\"items\":[{\"offerId\":501,\"quantity\":2}]}");
    }

    @Test
    @DisplayName("무료배송(0원)은 정상 응답이다")
    void zeroIsValid() {
        responseBody.set("{\"shippingFee\":0}");

        assertThat(client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))).shippingFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("오류 응답이면 0원으로 넘어가지 않고 주문을 막는다")
    void errorStatusFailsOrder() {
        status.set(500);

        assertThatThrownBy(() -> client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))))
                .isInstanceOf(OrderStateException.class)
                .hasMessage("order.catalogUnavailable");
    }

    @Test
    @DisplayName("이 엔드포인트가 없는 product.api(404)에도 0원으로 넘어가지 않는다 — 배포 순서가 뒤집힌 경우")
    void missingEndpointFailsOrder() {
        status.set(404);

        assertThatThrownBy(() -> client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))))
                .isInstanceOf(OrderStateException.class);
    }

    @Test
    @DisplayName("응답에 금액이 없거나 음수면 주문을 막는다")
    void missingOrNegativeFeeFailsOrder() {
        responseBody.set("{}");
        assertThatThrownBy(() -> client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))))
                .isInstanceOf(OrderStateException.class);

        responseBody.set("{\"shippingFee\":-1}");
        assertThatThrownBy(() -> client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))))
                .isInstanceOf(OrderStateException.class);
    }

    @Test
    @DisplayName("반품 배송비를 함께 받고, 그 필드가 없는 옛 응답이면 0 으로 본다 (gateway#320)")
    void returnShippingFee() {
        responseBody.set("{\"shippingFee\":0,\"returnShippingFee\":2500}");
        assertThat(client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))).returnShippingFee()).isEqualByComparingTo("2500");

        responseBody.set("{\"shippingFee\":0}");
        assertThat(client.quoteShipping(List.of(new ShippingQuoteItem(501L, 1))).returnShippingFee()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("주문 당시 단가를 줄 때만 unitPrice 를 싣는다")
    void sendsUnitPriceOnlyWhenGiven() {
        responseBody.set("{\"shippingFee\":0}");
        client.quoteShipping(List.of(new ShippingQuoteItem(501L, 2, new java.math.BigDecimal("15000"))));
        assertThat(requestBody.get()).isEqualTo("{\"items\":[{\"offerId\":501,\"quantity\":2,\"unitPrice\":15000}]}");
    }
}
