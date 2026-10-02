package com.dh.order.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * auth.api 의 회원 등급 조회(gateway#82). 주문 금액을 확정할 때 등급 할인율을 받아 온다 —
 * 클러스터 내부 DNS 로만 호출하므로 게이트웨이/인증을 거치지 않는다({@link ProductApiClient} 와 같다).
 *
 * <p><b>실패하면 "할인 없음"이다.</b> 가격 조회 실패는 주문을 거부하지만(가격을 모르면 금액을 확정할
 * 수 없다) 등급 조회 실패는 주문을 막지 않는다. auth.api 가 죽었다고 주문까지 못 받으면 혜택 하나
 * 때문에 장애 범위가 주문 전체로 번진다. 대신 할인이 빠진 사실은 주문에 그대로 남고
 * ({@code grade_code} NULL) 경고 로그로 드러난다.
 */
@Component
public class AuthApiClient {

    private static final Logger log = LoggerFactory.getLogger(AuthApiClient.class);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AuthApiClient(
            @Value("${auth-api.base-url:http://auth-api.customer.svc.cluster.local:8080}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * 회원의 현재 등급. 로컬 회원 행이 없는 sub(404)와 호출 실패는 모두 빈 값이다.
     *
     * @param userId Keycloak sub. 게이트웨이가 JWT 검증 후 주입한 {@code X-User-Id} 에서만 온다.
     */
    @CircuitBreaker(name = "authApi", fallbackMethod = "findMemberGradeFallback")
    public Optional<MemberGrade> findMemberGrade(String userId) {
        URI uri = URI.create(baseUrl + "/internal/member-grades/members/"
                + URLEncoder.encode(userId, StandardCharsets.UTF_8));
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("auth.api 등급 조회 실패 (status=" + response.statusCode() + ")");
            }
            return Optional.of(objectMapper.readValue(response.body(), MemberGrade.class));
        } catch (IOException e) {
            throw new IllegalStateException("auth.api 연결 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("auth.api 호출 중단", e);
        }
    }

    public Optional<MemberGrade> findMemberGradeFallback(String userId, Throwable t) {
        log.warn("등급 조회 불가 - 이 주문은 등급 할인 없이 확정된다 (userId={})", userId, t);
        return Optional.empty();
    }

    /** {@code discountRate} 는 퍼센트 값이다(5.00 = 5%) — auth.api {@code member_grades.discount_rate} 그대로. */
    public record MemberGrade(String code, String name, BigDecimal discountRate) {
    }
}
