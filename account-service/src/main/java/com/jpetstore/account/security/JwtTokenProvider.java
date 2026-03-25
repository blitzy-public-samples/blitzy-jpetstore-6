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

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT Token Provider for the Account Service microservice.
 *
 * <p>This component handles JWT token generation, validation, and claims extraction,
 * replacing the monolith's session-scoped {@code AccountActionBean.authenticated} flag
 * with stateless JWT-based authentication. It is the central authority for issuing
 * and verifying authentication tokens within the Account bounded context.</p>
 *
 * <h3>Token Structure</h3>
 * <ul>
 *   <li>{@code sub} (subject) — the authenticated user's username (maps to
 *       {@code Account.username} from the monolith domain)</li>
 *   <li>{@code iat} (issued at) — timestamp when the token was created</li>
 *   <li>{@code exp} (expiration) — timestamp after which the token is invalid,
 *       calculated as {@code iat + jwt.expiration-ms}</li>
 * </ul>
 *
 * <h3>Signing Algorithm</h3>
 * <p>Uses HMAC-SHA256 (HS256). The signing key is derived from the {@code jwt.secret}
 * application property via {@link Keys#hmacShaKeyFor(byte[])}. The secret must be at
 * least 256 bits (32 bytes) for HS256 security requirements.</p>
 *
 * <h3>Cross-Service Coordination</h3>
 * <p>The API Gateway's {@code AuthenticationFilter} validates tokens using the same
 * JJWT 0.12.6 API and the same {@code jwt.secret} value. Both components must share
 * the identical secret for tokens to be cross-validatable. The API Gateway extracts
 * the username from the JWT subject claim and propagates it as an
 * {@code X-Auth-Username} header to downstream services.</p>
 *
 * <h3>Replaces Monolith Pattern</h3>
 * <p>In the monolith, authentication state was managed by:
 * <ul>
 *   <li>{@code AccountActionBean.authenticated} (session-scoped boolean flag)</li>
 *   <li>{@code AccountActionBean.isAuthenticated()} (checks flag + account != null)</li>
 *   <li>{@code session.setAttribute("accountBean", this)} (stores bean in HTTP session)</li>
 * </ul>
 * All of these are replaced by a single JWT token issued on successful signon
 * and validated on each subsequent request.</p>
 *
 * @see io.jsonwebtoken.Jwts
 * @see io.jsonwebtoken.security.Keys
 */
@Component
public class JwtTokenProvider {

    /**
     * HMAC-SHA256 secret key for signing and verifying JWT tokens.
     *
     * <p>Loaded from the {@code jwt.secret} property in {@code application.yml}.
     * The secret must be at least 32 bytes (256 bits) to satisfy HS256 security
     * requirements. In a production environment, this should be externalized via
     * environment variables or a secrets vault (out of scope for this POC per
     * AAP Section 0.3.2).</p>
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Token expiration time in milliseconds.
     *
     * <p>Loaded from the {@code jwt.expiration-ms} property in {@code application.yml}.
     * Defaults to 86400000 ms (24 hours) if not explicitly configured. The actual
     * configured value in the Account Service is 3600000 ms (1 hour).</p>
     */
    @Value("${jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    /**
     * Token issuer identifier.
     *
     * <p>Loaded from the {@code jwt.issuer} property in {@code application.yml}.
     * This value is embedded in the JWT's {@code iss} claim and must match the
     * expected issuer configured in the API Gateway's AuthenticationFilter
     * ({@code jwt.issuer} in the gateway's {@code application.yml}).
     * Defaults to "jpetstore" to match the gateway's default.</p>
     */
    @Value("${jwt.issuer:jpetstore}")
    private String jwtIssuer;

    /**
     * Creates the HMAC-SHA256 signing key from the configured secret string.
     *
     * <p>Converts the {@code jwt.secret} string to bytes using UTF-8 encoding
     * and creates a {@link SecretKey} suitable for HMAC-SHA256 operations.
     * JJWT 0.12.6 automatically infers the HS256 algorithm from the key type.</p>
     *
     * @return the HMAC-SHA256 {@link SecretKey} for JWT signing and verification
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a JWT token for the authenticated user.
     *
     * <p>This method is called by {@code AccountController} after successful
     * authentication (signon) or registration (newAccount), replacing the
     * monolith's pattern of setting {@code authenticated = true} on the
     * session-scoped {@code AccountActionBean} (lines 119, 171).</p>
     *
     * <p>The generated token contains:</p>
     * <ul>
     *   <li>{@code sub} claim — the username, which downstream services and
     *       the API Gateway use to identify the authenticated user</li>
     *   <li>{@code iat} claim — the current timestamp</li>
     *   <li>{@code exp} claim — current time + configured expiration</li>
     * </ul>
     *
     * <p>Uses JJWT 0.12.6 builder API: {@code Jwts.builder().subject().issuedAt()
     * .expiration().signWith().compact()}</p>
     *
     * @param username the authenticated user's username (corresponds to
     *                 {@code Account.username} from the monolith domain; this is
     *                 the primary key of the account table)
     * @return a compact JWT string suitable for inclusion in HTTP Authorization
     *         headers or HTTP-only cookies
     * @throws IllegalArgumentException if username is null
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(username)
                .issuer(jwtIssuer)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validates a JWT token by verifying its signature and checking expiration.
     *
     * <p>This method replaces the monolith's {@code AccountActionBean.isAuthenticated()}
     * check (line 195-197): {@code return authenticated && account != null &&
     * account.getUsername() != null}. Instead of checking a session-scoped flag,
     * it cryptographically verifies the token's integrity and ensures it has not
     * expired.</p>
     *
     * <p>Uses JJWT 0.12.6 parser API: {@code Jwts.parser().verifyWith(key).build()
     * .parseSignedClaims(token)}</p>
     *
     * <p>The following error conditions are handled gracefully by returning
     * {@code false}:</p>
     * <ul>
     *   <li>Expired token ({@code ExpiredJwtException})</li>
     *   <li>Invalid JWT structure ({@code MalformedJwtException})</li>
     *   <li>Unsupported algorithm or format ({@code UnsupportedJwtException})</li>
     *   <li>Signature mismatch ({@code SecurityException})</li>
     *   <li>Null or empty token ({@code IllegalArgumentException})</li>
     * </ul>
     *
     * @param token the JWT token string to validate
     * @return {@code true} if the token has a valid signature and has not expired;
     *         {@code false} for any invalid, expired, malformed, or null token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException covers: ExpiredJwtException, MalformedJwtException,
            // UnsupportedJwtException, SignatureException (via SecurityException)
            // IllegalArgumentException covers: null or empty token string
            return false;
        }
    }

    /**
     * Extracts the username (subject claim) from a JWT token.
     *
     * <p>This method is used by the Account Service controller to identify
     * the authenticated user from a validated token. The extracted username
     * corresponds to the {@code Account.username} field (line 31 of the
     * monolith's {@code Account.java}), which is the value that
     * {@code OrderActionBean} previously obtained via
     * {@code session.getAttribute("/actions/Account.action")} →
     * {@code accountBean.getAccount().getUsername()}.</p>
     *
     * <p>Uses JJWT 0.12.6 API: {@code .parseSignedClaims(token).getPayload()
     * .getSubject()}</p>
     *
     * @param token the JWT token string from which to extract the username
     * @return the username stored in the token's {@code sub} (subject) claim
     * @throws JwtException if the token is invalid, expired, or has a
     *                      mismatched signature
     * @throws IllegalArgumentException if the token is null or empty
     */
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
