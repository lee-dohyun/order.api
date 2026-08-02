package com.dh.order.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
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

    /** 유효하면 email 클레임을, 아니면 null을 반환한다. */
    public String verify(String bearerToken) {
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
            return claims.getStringClaim("email");
        } catch (Exception e) {
            return null;
        }
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
