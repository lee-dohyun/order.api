package com.dh.order.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dh.order.domain.OrderItem;
import com.dh.order.service.OrderStateException;
import com.fasterxml.jackson.core.type.TypeReference;
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

    /**
     * variantId만 넘겨 상품/가격을 확정받는다. 클라이언트가 보낸 가격·상품명·productId는 신뢰하지
     * 않고 전부 이 응답으로 대체한다 — Redmine posselect #232.
     *
     * <p>존재하지 않는 variantId는 응답에서 빠지므로 호출자가 요청 건수와 대조해야 한다.
     *
     * @throws OrderStateException product.api 호출에 실패하면. 가격을 모르는 채로 주문을 만드는
     *         것보다 주문 생성을 실패시키는 편이 안전하다.
     */
    public Map<Long, ResolvedVariant> resolveVariants(List<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        String ids = variantIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        URI uri = URI.create(baseUrl + "/api/internal/variants/resolve?ids=" + ids);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("상품 가격 조회 실패 (status={}, body={})", response.statusCode(), response.body());
                throw new OrderStateException("order.catalogUnavailable");
            }
            List<ResolvedVariant> resolved = objectMapper.readValue(
                    response.body(), new TypeReference<List<ResolvedVariant>>() {
                    });
            return resolved.stream().collect(Collectors.toMap(ResolvedVariant::variantId, v -> v));
        } catch (IOException e) {
            log.warn("상품 서비스 연결 실패 (variantIds={})", variantIds, e);
            throw new OrderStateException("order.catalogUnavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("상품 서비스 호출 중단 (variantIds={})", variantIds, e);
            throw new OrderStateException("order.catalogUnavailable");
        }
    }

    /** product.api가 확정해 준 상품/가격. 주문 금액 산정의 유일한 출처다. */
    public record ResolvedVariant(
            Long variantId,
            Long productId,
            String productName,
            BigDecimal price,
            boolean active) {
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

    public void restoreInventory(Long orderId, List<com.dh.order.dto.OrderDtos.OrderItemResponse> items) {
        List<Map<String, Object>> itemPayload = items.stream()
                .map(item -> Map.<String, Object>of("variantId", item.variantId(), "quantity", item.quantity()))
                .toList();
        Map<String, Object> body = Map.of("orderId", orderId, "items", itemPayload);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/inventory/restore"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("재고 복원 실패 (orderId={}, status={}, body={})", orderId, response.statusCode(), response.body());
                throw new OrderStateException("order.inventoryUnavailable");
            }
        } catch (IOException e) {
            log.warn("재고 서비스 연결 실패 (orderId={})", orderId, e);
            throw new OrderStateException("order.inventoryUnavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("재고 서비스 호출 중단 (orderId={})", orderId, e);
            throw new OrderStateException("order.inventoryUnavailable");
        }
    }
}
