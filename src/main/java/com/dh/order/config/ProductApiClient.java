package com.dh.order.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dh.order.domain.OrderItem;
import com.fasterxml.jackson.databind.ObjectMapper;

// order.api와 product.api의 첫 서비스 간 동기 호출. 결제 확정 시점(payOrder)에 재고를 실제로
// 차감시키기 위함 - 클러스터 내부 DNS로만 호출하므로 게이트웨이/인증을 거치지 않는다.
@Component
public class ProductApiClient {

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ProductApiClient(
            @Value("${product-api.base-url:http://product-api.customer.svc.cluster.local:8080}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    /** @throws IllegalStateException 재고 부족이거나 product.api 호출에 실패하면 (ApiExceptionHandler가 409로 응답) */
    public void deductInventory(Long orderId, List<OrderItem> items) {
        List<Map<String, Object>> itemPayload = items.stream()
                .map(item -> Map.<String, Object>of("productId", item.getProductId(), "quantity", item.getQuantity()))
                .toList();
        Map<String, Object> body = Map.of("orderId", orderId, "items", itemPayload);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/inventory/deduct"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("재고가 부족하거나 처리에 실패했습니다: " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("재고 서비스 연결에 실패했습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 서비스 연결이 중단되었습니다.", e);
        }
    }
}
