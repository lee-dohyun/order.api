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

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
     * offerId 로 가격·상품·판매자를 확정받는다(order.api#14). 클라이언트가 보낸 가격·상품명·판매자는
     * 신뢰하지 않고 전부 이 응답으로 대체한다 — Redmine posselect #232 / product.api#5.
     *
     * <p>존재하지 않는 id 는 응답에서 빠지므로 호출자가 요청 건수와 대조해야 한다. 결과 맵의 키는 offerId 다.
     *
     * @throws OrderStateException product.api 호출에 실패하면. 가격을 모르는 채로 주문을 만드는
     *         것보다 주문 생성을 실패시키는 편이 안전하다.
     */
    @CircuitBreaker(name = "productApi", fallbackMethod = "resolveOffersFallback")
    public Map<Long, ResolvedOffer> resolveOffers(List<Long> offerIds) {
        return fetchOffers("ids", offerIds).stream()
                .collect(Collectors.toMap(ResolvedOffer::offerId, o -> o));
    }

    public Map<Long, ResolvedOffer> resolveOffersFallback(List<Long> offerIds, Throwable t) {
        log.warn("서킷브레이커/폴백 동작 - 상품 서비스 호출 불가 (offerIds={})", offerIds, t);
        throw new OrderStateException("order.catalogUnavailable");
    }

    /**
     * variantId 만 아는 품목을 위해 SKU 별 <b>대표 오퍼</b>를 product.api 가 골라 확정해 준다
     * (product.api#69). 지금 장바구니와 주문을 만드는 product.front 가 variantId 만 보내므로, 이
     * 경로 없이 오퍼로 전환하면 그 순간 결제가 깨진다. 결과 맵의 키는 variantId 다.
     *
     * <p>ACTIVE 오퍼가 없는 SKU 는 응답에서 빠진다 — 호출자가 누락으로 판정한다.
     */
    @CircuitBreaker(name = "productApi", fallbackMethod = "resolveFeaturedOffersByVariantFallback")
    public Map<Long, ResolvedOffer> resolveFeaturedOffersByVariant(List<Long> variantIds) {
        return fetchOffers("variantIds", variantIds).stream()
                .collect(Collectors.toMap(ResolvedOffer::variantId, o -> o));
    }

    public Map<Long, ResolvedOffer> resolveFeaturedOffersByVariantFallback(List<Long> variantIds, Throwable t) {
        log.warn("서킷브레이커/폴백 동작 - 상품 서비스 호출 불가 (variantIds={})", variantIds, t);
        throw new OrderStateException("order.catalogUnavailable");
    }

    private List<ResolvedOffer> fetchOffers(String param, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        URI uri = URI.create(baseUrl + "/internal/offers/resolve?" + param + "=" + joined);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("오퍼 확정 조회 실패 (status={}, body={})", response.statusCode(), response.body());
                throw new OrderStateException("order.catalogUnavailable");
            }
            return objectMapper.readValue(response.body(), new TypeReference<List<ResolvedOffer>>() {
            });
        } catch (IOException e) {
            log.warn("상품 서비스 연결 실패 ({}={})", param, ids, e);
            throw new OrderStateException("order.catalogUnavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("상품 서비스 호출 중단 ({}={})", param, ids, e);
            throw new OrderStateException("order.catalogUnavailable");
        }
    }

    /**
     * product.api 가 확정해 준 오퍼({@code OfferResolveResponse}). 주문 금액과 판매자 스냅샷의
     * 유일한 출처다. variantId 는 재고 차감 기준 키로 계속 쓴다(1P 재고는 variant 단위).
     *
     * <p>{@code active=false} 면 주문 불가다 — 오퍼 상태만이 아니라 숨김 상품·판매 기간 밖·판매자
     * 정지·해지까지 합친 값이다(product.api#108). {@code maxPurchaseQuantity} 는 상품 단위 1회 최대
     * 구매 수량(null = 제한 없음, product.api#97). 이 두 값이 오퍼 응답에 실리기 전에 주문을 오퍼
     * 기준으로 바꾸면 그 차단들이 한꺼번에 사라진다.
     */
    public record ResolvedOffer(
            Long offerId,
            Long variantId,
            Long productId,
            String productName,
            Long sellerId,
            String sellerName,
            BigDecimal price,
            BigDecimal shippingFee,
            boolean freeShipping,
            Short leadTimeDays,
            boolean active,
            Integer maxPurchaseQuantity) {
    }

    /** @throws OrderStateException 재고 부족이거나 product.api 호출에 실패하면 (ApiExceptionHandler가 409로 응답) */
    @CircuitBreaker(name = "productApi", fallbackMethod = "deductInventoryFallback")
    public void deductInventory(Long orderId, List<OrderItem> items) {
        List<Map<String, Object>> itemPayload = items.stream()
                .map(item -> Map.<String, Object>of("variantId", item.getVariantId(), "quantity", item.getQuantity()))
                .toList();
        Map<String, Object> body = Map.of("orderId", orderId, "items", itemPayload);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/inventory/deduct"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
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

    public void deductInventoryFallback(Long orderId, List<OrderItem> items, Throwable t) {
        log.warn("서킷브레이커/폴백 동작 - 재고 서비스 호출 불가 (orderId={})", orderId, t);
        throw new OrderStateException("order.inventoryUnavailable");
    }

    @CircuitBreaker(name = "productApi", fallbackMethod = "restoreInventoryFallback")
    public void restoreInventory(Long orderId, List<com.dh.order.dto.OrderDtos.OrderItemResponse> items) {
        List<Map<String, Object>> itemPayload = items.stream()
                .map(item -> Map.<String, Object>of("variantId", item.variantId(), "quantity", item.quantity()))
                .toList();
        Map<String, Object> body = Map.of("orderId", orderId, "items", itemPayload);

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/inventory/restore"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
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

    public void restoreInventoryFallback(Long orderId, List<com.dh.order.dto.OrderDtos.OrderItemResponse> items, Throwable t) {
        log.warn("서킷브레이커/폴백 동작 - 재고 복원 불가 (orderId={})", orderId, t);
        throw new OrderStateException("order.inventoryUnavailable");
    }
}
