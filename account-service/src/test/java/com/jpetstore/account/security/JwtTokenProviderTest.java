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
package com.jpetstore.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Unit tests for {@link JwtTokenProvider}.
 *
 * <p>This is a plain JUnit 5 test — no Spring application context is loaded.
 * The private {@code @Value}-injected fields ({@code jwtSecret} and
 * {@code jwtExpirationMs}) are set via Java reflection in {@link #setUp()},
 * enabling direct testing of token generation, validation, and claims
 * extraction without any Spring infrastructure.</p>
 *
 * <p>These tests validate the JWT-based authentication that replaces the
 * monolith's session-scoped {@code AccountActionBean.authenticated} flag.
 * In the monolith, authentication state was managed by a boolean flag and
 * checked via {@code AccountActionBean.isAuthenticated()} (which verified
 * {@code authenticated && account != null && account.getUsername() != null}).
 * The JWT approach encodes the same information into a cryptographically
 * signed token: {@code validateToken()} replaces the {@code authenticated}
 * flag check, and {@code getUsernameFromToken()} replaces the
 * {@code account.getUsername() != null} check.</p>
 *
 * <p>Uses JJWT 0.12.6 API for independent token parsing in test assertions:
 * {@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()}</p>
 *
 * @see JwtTokenProvider
 */
class JwtTokenProviderTest {

    /**
     * HMAC-SHA256 secret for testing. Must be at least 32 bytes (256 bits)
     * to satisfy the HS256 security requirement enforced by JJWT.
     */
    private static final String TEST_SECRET = "myTestSecretKeyThatIsAtLeast32BytesLongForHS256";

    /**
     * Token expiration time for testing: 1 hour (3600000 milliseconds).
     */
    private static final long TEST_EXPIRATION_MS = 3600000L;

    /**
     * Test username matching the monolith's default test user from
     * {@code jpetstore-hsqldb-dataload.sql}. This is the same value
     * stored in {@code Account.username} (Account.java line 31) and
     * checked by {@code AccountActionBean.isAuthenticated()}.
     */
    private static final String TEST_USERNAME = "j2ee";

    private JwtTokenProvider jwtTokenProvider;

    /**
     * Sets up a fresh {@link JwtTokenProvider} instance before each test,
     * using reflection to inject the private {@code jwtSecret} and
     * {@code jwtExpirationMs} fields that are normally populated by
     * Spring's {@code @Value} annotation.
     *
     * @throws Exception if reflection access to private fields fails
     */
    @BeforeEach
    void setUp() throws Exception {
        jwtTokenProvider = new JwtTokenProvider();
        setField(jwtTokenProvider, "jwtSecret", TEST_SECRET);
        setField(jwtTokenProvider, "jwtExpirationMs", TEST_EXPIRATION_MS);
    }

    /**
     * Helper method to set a private field on a target object via reflection.
     * This avoids duplicating reflection boilerplate across setup and test methods.
     *
     * @param target    the object whose field is to be set
     * @param fieldName the name of the private field
     * @param value     the value to assign to the field
     * @throws Exception if the field does not exist or cannot be accessed
     */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // -----------------------------------------------------------------------
    // generateToken() tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateToken should return a non-null, non-blank JWT string with two dots")
    void generateToken_shouldReturnNonEmptyString() {
        // Act
        String token = jwtTokenProvider.generateToken(TEST_USERNAME);

        // Assert — token is non-null and non-blank
        assertThat(token).isNotNull().isNotBlank();

        // Assert — JWT format is header.payload.signature (exactly 2 dots)
        long dotCount = token.chars().filter(c -> c == '.').count();
        assertThat(dotCount).isEqualTo(2);
    }

    @Test
    @DisplayName("generateToken should produce a JWT with correct sub, iat, and exp claims")
    void generateToken_shouldContainCorrectClaims() {
        // Arrange — capture timestamps bracketing token generation
        Date beforeGeneration = new Date();

        // Act
        String token = jwtTokenProvider.generateToken(TEST_USERNAME);

        // Arrange — upper bound timestamp
        Date afterGeneration = new Date();

        // Parse the token independently using JJWT 0.12.6 API to verify claims
        // outside of the JwtTokenProvider under test
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Assert — sub (subject) claim equals the username
        // This is the Account.username (Account.java line 31) that replaces
        // the monolith's session-scoped AccountActionBean.authenticated check
        assertThat(claims.getSubject()).isEqualTo(TEST_USERNAME);

        // Assert — iat (issued at) claim is within the generation window
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getIssuedAt()).isAfterOrEqualTo(truncateToSeconds(beforeGeneration));
        assertThat(claims.getIssuedAt()).isBeforeOrEqualTo(afterGeneration);

        // Assert — exp (expiration) claim is in the future
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(new Date());

        // Assert — expiration is approximately iat + TEST_EXPIRATION_MS
        // Allow 2-second tolerance for test execution time
        long expectedExpirationMs = claims.getIssuedAt().getTime() + TEST_EXPIRATION_MS;
        assertThat(claims.getExpiration().getTime())
                .isCloseTo(expectedExpirationMs, Offset.offset(2000L));
    }

    // -----------------------------------------------------------------------
    // validateToken() tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateToken should return true for a valid, non-expired token")
    void validateToken_withValidToken_shouldReturnTrue() {
        // Arrange — generate a valid token
        String token = jwtTokenProvider.generateToken(TEST_USERNAME);

        // Act & Assert — replaces the monolith's AccountActionBean.isAuthenticated()
        // check at line 195-197: authenticated && account != null && account.getUsername() != null
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken should return false for an expired token")
    void validateToken_withExpiredToken_shouldReturnFalse() throws Exception {
        // Arrange — create a separate JwtTokenProvider configured with a negative
        // expiration to produce tokens that are already expired at generation time
        JwtTokenProvider expiredProvider = new JwtTokenProvider();
        setField(expiredProvider, "jwtSecret", TEST_SECRET);
        setField(expiredProvider, "jwtExpirationMs", -1000L);

        // Act — generate an already-expired token
        String expiredToken = expiredProvider.generateToken(TEST_USERNAME);

        // Assert — the main provider (same signing key) correctly identifies
        // the token as expired and returns false. This mirrors the behavior where
        // the monolith's AccountActionBeanTest.isAuthenticatedOutputFalse() verifies
        // that a fresh ActionBean starts unauthenticated (line 64-71)
        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken should return false for a malformed token string")
    void validateToken_withMalformedToken_shouldReturnFalse() {
        // Act & Assert — malformed strings must not pass validation
        assertThat(jwtTokenProvider.validateToken("not.a.valid.jwt")).isFalse();
    }

    @Test
    @DisplayName("validateToken should return false for a tampered token (modified payload)")
    void validateToken_withTamperedToken_shouldReturnFalse() {
        // Arrange — generate a valid token
        String validToken = jwtTokenProvider.generateToken(TEST_USERNAME);

        // Tamper with the payload section (second segment between the dots)
        // This invalidates the HMAC-SHA256 signature
        String[] parts = validToken.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = (payload[0] == 'a') ? 'b' : 'a';
        String tamperedToken = parts[0] + "." + new String(payload) + "." + parts[2];

        // Act & Assert — tampered token must fail signature verification
        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken should return false for an empty string")
    void validateToken_withEmptyString_shouldReturnFalse() {
        // Act & Assert — empty string must not pass validation
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("validateToken should return false for a token signed with a different secret")
    void validateToken_withDifferentSecret_shouldReturnFalse() throws Exception {
        // Arrange — create a provider with a completely different signing secret
        JwtTokenProvider otherProvider = new JwtTokenProvider();
        setField(otherProvider, "jwtSecret", "aCompletelyDifferentSecretKeyForTestingPurposes!");
        setField(otherProvider, "jwtExpirationMs", TEST_EXPIRATION_MS);

        // Generate a token using the other secret
        String otherToken = otherProvider.generateToken(TEST_USERNAME);

        // Act & Assert — our provider (with TEST_SECRET) must reject the token
        // because the HMAC-SHA256 signature does not match
        assertThat(jwtTokenProvider.validateToken(otherToken)).isFalse();
    }

    // -----------------------------------------------------------------------
    // getUsernameFromToken() tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getUsernameFromToken should extract the correct username from the sub claim")
    void getUsernameFromToken_withValidToken_shouldReturnUsername() {
        // Arrange
        String token = jwtTokenProvider.generateToken(TEST_USERNAME);

        // Act — extract the username (subject claim) from the token.
        // This is what the API Gateway extracts and propagates as
        // X-Auth-Username header, replacing the monolith's session-scoped
        // accountBean.getAccount().getUsername()
        String username = jwtTokenProvider.getUsernameFromToken(token);

        // Assert — extracted username matches the original
        assertThat(username).isEqualTo(TEST_USERNAME);
    }

    @Test
    @DisplayName("getUsernameFromToken should correctly handle different usernames")
    void getUsernameFromToken_withDifferentUsername_shouldReturnCorrectUsername() {
        // Arrange — use the other default test user from jpetstore-hsqldb-dataload.sql
        String otherUsername = "ACID";

        // Act
        String token = jwtTokenProvider.generateToken(otherUsername);
        String extracted = jwtTokenProvider.getUsernameFromToken(token);

        // Assert
        assertThat(extracted).isEqualTo(otherUsername);
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Truncates a Date to second precision by zeroing out the milliseconds.
     * JWT {@code iat} and {@code exp} claims are stored as seconds-since-epoch
     * (NumericDate per RFC 7519), so sub-second precision is lost during
     * token creation. This helper ensures time-based assertions account for
     * that truncation.
     *
     * @param date the Date to truncate
     * @return a new Date with milliseconds zeroed
     */
    private Date truncateToSeconds(Date date) {
        return new Date((date.getTime() / 1000) * 1000);
    }
}
