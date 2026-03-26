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
package com.jpetstore.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link AccountServiceClient} — the Order Service's REST client
 * for communicating with the Account Service microservice.
 *
 * <p>This test class validates that the client correctly handles all HTTP response
 * scenarios including success (200), not found (404), internal server error (500),
 * and service unavailability (503). All error paths must return gracefully
 * (empty Optional or false) rather than throwing exceptions, supporting the
 * Saga pattern's fail-fast design.
 *
 * <p><b>Test Strategy:</b> Lightweight unit tests using {@link MockRestServiceServer}
 * bound to a manually-built {@link RestClient}. No Spring context is started
 * ({@code @SpringBootTest} is NOT used). This is the standard Spring Framework 6.1+
 * approach for testing {@code RestClient}-based clients:
 * <ol>
 *   <li>Create a {@code RestClient.Builder} with the base URL</li>
 *   <li>Bind {@code MockRestServiceServer} to the builder to intercept HTTP calls</li>
 *   <li>Build the {@code RestClient} from the same builder</li>
 *   <li>Pass the built {@code RestClient} to the {@code AccountServiceClient} constructor</li>
 * </ol>
 *
 * <p><b>Domain Context:</b> In the monolith, {@code OrderActionBean.newOrderForm()}
 * (line 121-127) retrieved the authenticated account from the HTTP session via
 * {@code session.getAttribute("/actions/Account.action")} and checked
 * {@code accountBean != null && accountBean.isAuthenticated()} before order assembly.
 * In the microservices architecture, the Order Service verifies user existence via
 * a REST call to {@code GET /api/accounts/{username}} on the Account Service.
 * These tests validate that the REST client handles all response scenarios correctly.
 *
 * @see AccountServiceClient
 */
class AccountServiceClientTest {

    /** Base URL for the Account Service as configured in application.yml */
    private static final String BASE_URL = "http://account-service:8081";

    /** Sample JSON response body representing a valid account from the Account Service */
    private static final String ACCOUNT_JSON =
            "{\"username\":\"j2ee\",\"firstName\":\"ABC\",\"lastName\":\"XYZ\","
            + "\"email\":\"j2ee@test.com\",\"status\":\"OK\","
            + "\"favouriteCategoryId\":\"DOGS\"}";

    /**
     * Mock HTTP server that intercepts all REST calls made by the
     * {@link AccountServiceClient} under test. Configured in {@link #setUp()}
     * by binding to the {@link RestClient.Builder}.
     */
    private MockRestServiceServer mockServer;

    /**
     * The class under test — constructed directly via
     * {@code new AccountServiceClient(restClient)}, bypassing Spring DI.
     */
    private AccountServiceClient client;

    /**
     * Configures the mock HTTP server and constructs the AccountServiceClient
     * before each test method. The MockRestServiceServer is bound to the
     * RestClient.Builder so that all HTTP calls made through the built
     * RestClient are intercepted by the mock server.
     *
     * <p>This setup mirrors the production wiring where
     * {@code AppConfig.accountServiceRestClient()} creates a RestClient
     * with base URL {@code http://account-service:8081}.
     */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new AccountServiceClient(restClient);
    }

    // =========================================================================
    // getAccount() tests
    // =========================================================================

    /**
     * Verifies that {@link AccountServiceClient#getAccount(String)} correctly
     * returns an {@link Optional} containing account data when the Account
     * Service responds with HTTP 200 and a valid JSON body.
     *
     * <p>Mirrors the monolith's {@code AccountService.getAccount(String)}
     * which calls {@code accountMapper.getAccountByUsername(username)}
     * returning a non-null {@code Account} object.
     */
    @Test
    @DisplayName("getAccount returns present Optional with account data when username exists (HTTP 200)")
    void shouldReturnAccountWhenUsernameExists() {
        // Arrange: configure mock to return 200 with account JSON
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        // Act: call the client
        Optional<Map<String, Object>> result = client.getAccount("j2ee");

        // Assert: result is present and contains expected account data
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().get("username")).isEqualTo("j2ee");
        assertThat(result.orElseThrow().get("email")).isEqualTo("j2ee@test.com");

        // Verify: expected request was made
        mockServer.verify();
    }

    /**
     * Verifies that {@link AccountServiceClient#getAccount(String)} returns
     * {@link Optional#empty()} when the Account Service responds with HTTP 404
     * (user not found). No exception should propagate to the caller.
     *
     * <p>Mirrors the monolith's {@code AccountMapper.getAccountByUsername()}
     * returning {@code null} when no matching username is found in the
     * signon/account/profile JOIN query.
     */
    @Test
    @DisplayName("getAccount returns empty Optional when user not found (HTTP 404)")
    void shouldReturnEmptyOptionalWhenUserNotFound() {
        // Arrange: configure mock to return 404
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/nonexistent"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Act: call the client — should not throw
        Optional<Map<String, Object>> result = client.getAccount("nonexistent");

        // Assert: empty Optional, graceful degradation
        assertThat(result).isEmpty();

        // Verify: expected request was made
        mockServer.verify();
    }

    /**
     * Verifies graceful degradation when the Account Service is unavailable
     * (HTTP 503 Service Unavailable). The client should return
     * {@link Optional#empty()} without throwing.
     *
     * <p>Per AAP §0.7.4 fallback pattern: when the Account Service is
     * unavailable, operations that depend on account data should fail
     * gracefully rather than crash. In the Saga pattern, this means
     * the order placement is rejected with an appropriate error message.
     */
    @Test
    @DisplayName("getAccount returns empty Optional when service unavailable (HTTP 503)")
    void shouldReturnEmptyOptionalWhenServiceUnavailable() {
        // Arrange: configure mock to return 503
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act: call the client — should not throw
        Optional<Map<String, Object>> result = client.getAccount("j2ee");

        // Assert: empty Optional, graceful degradation
        assertThat(result).isEmpty();

        // Verify: expected request was made
        mockServer.verify();
    }

    /**
     * Verifies that {@link AccountServiceClient#getAccount(String)} handles
     * HTTP 500 Internal Server Error gracefully by returning
     * {@link Optional#empty()} instead of propagating the exception.
     *
     * <p>This covers the general server error case where the Account Service
     * is reachable but encounters an internal failure during the account
     * lookup (e.g., database connection failure within the Account Service).
     */
    @Test
    @DisplayName("getAccount returns empty Optional on internal server error (HTTP 500)")
    void shouldReturnEmptyOptionalOnInternalServerError() {
        // Arrange: configure mock to return 500
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Act: call the client — should not throw
        Optional<Map<String, Object>> result = client.getAccount("j2ee");

        // Assert: empty Optional, graceful degradation
        assertThat(result).isEmpty();

        // Verify: expected request was made
        mockServer.verify();
    }

    // =========================================================================
    // accountExists() tests
    // =========================================================================

    /**
     * Verifies that {@link AccountServiceClient#accountExists(String)} returns
     * {@code true} when the Account Service responds with HTTP 200 and valid
     * account data.
     *
     * <p>This convenience method is used by {@code OrderService} and
     * {@code OrderSagaOrchestrator} for pre-validation before order placement,
     * replicating the monolith's check:
     * {@code accountBean != null && accountBean.isAuthenticated()}
     * in {@code OrderActionBean.newOrderForm()} (line 125).
     */
    @Test
    @DisplayName("accountExists returns true when account is found (HTTP 200)")
    void shouldReturnTrueForAccountExists() {
        // Arrange: configure mock to return 200 with account JSON
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        // Act: call the convenience method
        boolean exists = client.accountExists("j2ee");

        // Assert: account exists
        assertThat(exists).isTrue();

        // Verify: expected request was made
        mockServer.verify();
    }

    /**
     * Verifies that {@link AccountServiceClient#accountExists(String)} returns
     * {@code false} when the Account Service responds with HTTP 404 (user not
     * found).
     *
     * <p>Mirrors the monolith scenario where
     * {@code accountBean == null || !accountBean.isAuthenticated()} evaluates
     * to {@code true} and the user is redirected to the sign-on page.
     */
    @Test
    @DisplayName("accountExists returns false when account not found (HTTP 404)")
    void shouldReturnFalseForAccountExistsWhenNotFound() {
        // Arrange: configure mock to return 404
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/nonexistent"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Act: call the convenience method
        boolean exists = client.accountExists("nonexistent");

        // Assert: account does not exist (graceful degradation)
        assertThat(exists).isFalse();

        // Verify: expected request was made
        mockServer.verify();
    }

    /**
     * Verifies that {@link AccountServiceClient#accountExists(String)} returns
     * {@code false} when the Account Service is unavailable (HTTP 503).
     *
     * <p>In the Saga pattern, if the account cannot be verified, the order
     * should not proceed. Returning {@code false} triggers the fail-fast path
     * in the orchestrator, preventing order creation without a verified user.
     */
    @Test
    @DisplayName("accountExists returns false when service unavailable (HTTP 503)")
    void shouldReturnFalseForAccountExistsWhenServiceUnavailable() {
        // Arrange: configure mock to return 503
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act: call the convenience method
        boolean exists = client.accountExists("j2ee");

        // Assert: account not verifiable — treated as non-existent (graceful degradation)
        assertThat(exists).isFalse();

        // Verify: expected request was made
        mockServer.verify();
    }

    // =========================================================================
    // URI verification tests
    // =========================================================================

    /**
     * Verifies that the client sends the request to the correct URI with the
     * username properly interpolated into the path template
     * {@code /api/accounts/{username}}.
     *
     * <p>The request must be sent as:
     * {@code GET http://account-service:8081/api/accounts/j2ee}
     * with no query parameters, no request body, and using HTTP GET method.
     * This confirms the {@code .uri("/api/accounts/{username}", username)}
     * template expansion in {@link AccountServiceClient#getAccount(String)}.
     */
    @Test
    @DisplayName("Client sends GET request to correct URI /api/accounts/{username}")
    void shouldSendCorrectRequestUri() {
        // Arrange: configure mock to expect EXACTLY the correct URI and method
        mockServer.expect(requestTo(BASE_URL + "/api/accounts/j2ee"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

        // Act: call getAccount which triggers the REST call
        client.getAccount("j2ee");

        // Verify: the expected request was made with the exact URI
        // MockRestServiceServer.verify() will fail if the request URI or method
        // did not match the expectation configured above
        mockServer.verify();
    }
}
