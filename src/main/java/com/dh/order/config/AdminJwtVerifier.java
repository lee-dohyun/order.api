package com.dh.order.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// product-api의 AdminJwtVerifier와 동일한 방식(Nimbus JOSE + JWKS)으로 Keycloak "staff" realm의
// JWT를 직접 검증한다. 서비스마다 독립적으로 두는 이유는 이 프로젝트의 기존 관례(공유 라이브러리 없이
// 서비스별로 자기 완결적)를 따른 것.
@Component
public class AdminJwtVerifier {

    private final String expectedIssuer;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();
    private final String jwksUri;

    public AdminJwtVerifier(
            @Value("${admin.staff-realm-url:http://keycloak-service.keycloak.svc.cluster.local/realms/staff}")
            String staffRealmUrl,
            @Value("${admin.staff-realm-issuer:https://keycloak.posselect.com/realms/staff}")
            String expectedIssuer) {
        this.jwksUri = staffRealmUrl + "/protocol/openid-connect/certs";
        this.expectedIssuer = expectedIssuer;
    }

    /**
     * 유효하면 email + 역할을 담은 {@link AdminPrincipal} 을, 아니면 null 을 반환한다.
     *
     * <p><b>여기서 null 이 아니라는 것은 "staff realm 의 유효한 토큰"이라는 뜻일 뿐,
     * 무엇을 해도 된다는 뜻이 아니다.</b> 호출부는 반드시
     * {@link AdminPrincipal#hasAnyRole(String...)} 로 역할까지 확인해야 한다 —
     * 그 확인이 빠져 있던 것이 order.api#12 다.
     */
    public AdminPrincipal verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        try {
            SignedJWT signedJwt = SignedJWT.parse(bearerToken);
            RSAKey rsaKey = resolveKey(signedJwt.getHeader().getKeyID());
            if (rsaKey == null || !signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
                return null;
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                return null;
            }
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return null;
            }
            return new AdminPrincipal(claims.getStringClaim("email"), extractRealmRoles(claims));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Keycloak 은 realm 역할을 {@code realm_access.roles} 배열에 싣는다
     * (admin.front 의 {@code lib/auth.ts} 도 같은 경로를 읽는다 — 둘이 어긋나면
     * 화면과 API 의 판정이 갈린다).
     */
    private static Set<String> extractRealmRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return Set.of();
        }
        if (!(map.get("roles") instanceof List<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String name) {
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    private RSAKey resolveKey(String kid) throws Exception {
        RSAKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JWKSet jwkSet = JWKSet.parse(response.body());
        RSAKey key = (RSAKey) jwkSet.getKeyByKeyId(kid);
        if (key != null) {
            keyCache.put(kid, key);
        }
        return key;
    }
}
