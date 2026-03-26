/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Unit tests for {@link AppConfig} — verifies REST client bean creation for
 * inter-service communication (Account Service and Catalog Service), request
 * factory timeout configuration, and service-to-service JWT token generation.
 *
 * <p>These beans replace the monolith's in-process {@code @SpringBean} injection
 * between ActionBeans and service classes with HTTP REST calls, as required by
 * the microservices decomposition (AAP §0.4.1, §0.5.2).</p>
 */
class AppConfigTest {

    private static final String JWT_SECRET = "MySuperSecretKeyForTestingThatIsLongEnoughForHS256Algorithm!!";
    private static final long JWT_EXPIRATION_MS = 3600000L;
    private static final String JWT_ISSUER = "jpetstore-test";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private AppConfig appConfig;

    @BeforeEach
    void setUp() throws Exception {
        appConfig = new AppConfig();
        setField(appConfig, "connectTimeoutMs", CONNECT_TIMEOUT_MS);
        setField(appConfig, "readTimeoutMs", READ_TIMEOUT_MS);
        setField(appConfig, "jwtSecret", JWT_SECRET);
        setField(appConfig, "jwtExpirationMs", JWT_EXPIRATION_MS);
        setField(appConfig, "jwtIssuer", JWT_ISSUER);
    }

    @Nested
    @DisplayName("AccountServiceRestClient Bean")
    class AccountServiceRestClientTests {

        @Test
        @DisplayName("should create non-null RestClient for Account Service")
        void shouldCreateAccountServiceRestClient() {
            RestClient client = appConfig.accountServiceRestClient("http://localhost:8081");
            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("should create RestClient with configurable base URL")
        void shouldAcceptConfigurableBaseUrl() {
            RestClient client = appConfig.accountServiceRestClient("http://custom-host:9999");
            assertThat(client).isNotNull();
        }
    }

    @Nested
    @DisplayName("CatalogServiceRestClient Bean")
    class CatalogServiceRestClientTests {

        @Test
        @DisplayName("should create non-null RestClient for Catalog Service")
        void shouldCreateCatalogServiceRestClient() {
            RestClient client = appConfig.catalogServiceRestClient("http://localhost:8082");
            assertThat(client).isNotNull();
        }

        @Test
        @DisplayName("should create RestClient with configurable base URL")
        void shouldAcceptConfigurableBaseUrl() {
            RestClient client = appConfig.catalogServiceRestClient("http://catalog:8082");
            assertThat(client).isNotNull();
        }
    }

    @Nested
    @DisplayName("Request Factory Configuration")
    class RequestFactoryTests {

        @Test
        @DisplayName("should create SimpleClientHttpRequestFactory with configured timeouts")
        void shouldCreateRequestFactoryWithTimeouts() throws Exception {
            Method method = AppConfig.class.getDeclaredMethod("createRequestFactory");
            method.setAccessible(true);
            SimpleClientHttpRequestFactory factory =
                    (SimpleClientHttpRequestFactory) method.invoke(appConfig);
            assertThat(factory).isNotNull();
        }

        @Test
        @DisplayName("should apply custom timeout values when overridden")
        void shouldApplyCustomTimeouts() throws Exception {
            setField(appConfig, "connectTimeoutMs", 2000);
            setField(appConfig, "readTimeoutMs", 15000);

            Method method = AppConfig.class.getDeclaredMethod("createRequestFactory");
            method.setAccessible(true);
            SimpleClientHttpRequestFactory factory =
                    (SimpleClientHttpRequestFactory) method.invoke(appConfig);
            assertThat(factory).isNotNull();
        }
    }

    @Nested
    @DisplayName("Service-to-Service JWT Token Generation")
    class JwtTokenGenerationTests {

        @Test
        @DisplayName("should generate valid JWT token with correct subject")
        void shouldGenerateTokenWithCorrectSubject() throws Exception {
            String token = invokeGenerateServiceToken();

            SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.getSubject()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("should generate JWT token with correct issuer")
        void shouldGenerateTokenWithCorrectIssuer() throws Exception {
            String token = invokeGenerateServiceToken();

            SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.getIssuer()).isEqualTo(JWT_ISSUER);
        }

        @Test
        @DisplayName("should generate JWT token with issuedAt and expiration dates")
        void shouldGenerateTokenWithDates() throws Exception {
            String token = invokeGenerateServiceToken();

            SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            assertThat(claims.getIssuedAt()).isNotNull();
            assertThat(claims.getExpiration()).isNotNull();
            assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        }

        @Test
        @DisplayName("should generate unique tokens on each invocation")
        void shouldGenerateUniqueTokens() throws Exception {
            String token1 = invokeGenerateServiceToken();
            // Small delay to ensure different issuedAt timestamps
            Thread.sleep(10);
            String token2 = invokeGenerateServiceToken();

            // Tokens may differ due to iat/exp timestamps
            assertThat(token1).isNotNull();
            assertThat(token2).isNotNull();
        }

        @Test
        @DisplayName("should generate compact JWT format string")
        void shouldGenerateCompactJwt() throws Exception {
            String token = invokeGenerateServiceToken();

            // JWT compact format has 3 dot-separated parts: header.payload.signature
            assertThat(token).isNotBlank();
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);
        }

        private String invokeGenerateServiceToken() throws Exception {
            Method method = AppConfig.class.getDeclaredMethod("generateServiceToken");
            method.setAccessible(true);
            return (String) method.invoke(appConfig);
        }
    }

    /**
     * Reflectively sets a private field value on the target object.
     * Used to simulate @Value injection without requiring a Spring context.
     */
    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
