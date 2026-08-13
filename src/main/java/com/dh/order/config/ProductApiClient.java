package com.dh.order.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dh.order.domain.OrderItem;
import com.dh.order.service.OrderStateException;
import com.fasterxml.jackson.databind.ObjectMapper;

// order.api와 product.api의 첫 서비스 간 동기 호출. 결제 확정 시점(payOrder)에 재고를 실제로
// 차감시키기 위함 - 클러스터 내부 DNS로만 호출하므로 게이트웨이/인증을 거치지 않는다.
@Component
public class ProductApiClient {

    private static final Logger log = LoggerFactory.getLogger(ProductApiClient.class);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ProductApiClient(
            @Value("${product-api.base-url:http://product-api.customer.svc.cluster.local:8080}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    /** @throws OrderStateException 재고 부족이거나 product.api 호출에 실패하면 (ApiExceptionHandler가 409로 응답) */
    public void deductInventory(Long orderId, List<OrderItem> items) {
        List<Map<String, Object>> itemPayload = items.stream()
                .map(item -> Map.<String, Object>of("variantId", item.getVariantId(), "quantity", item.getQuantity()))
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
                // product.api의 원문 응답은 고객에게 보여줄 것이 아니므로 로그로만 남긴다.
                log.warn("재고 차감 실패 (orderId={}, status={}, body={})", orderId, response.statusCode(), response.body());
                throw new OrderStateException("order.outOfStock");
            }
        } catch (IOException e) {
            // OrderStateException은 메시지 키만 들고 다녀서 cause를 못 싣는다 — 원인은 여기서 로그로 남긴다.
            log.warn("재고 서비스 연결 실패 (orderId={})", orderId, e);
            throw new OrderStateException("order.inventoryUnavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("재고 서비스 호출 중단 (orderId={})", orderId, e);
            throw new OrderStateException("order.inventoryUnavailable");
        }
    }
}
