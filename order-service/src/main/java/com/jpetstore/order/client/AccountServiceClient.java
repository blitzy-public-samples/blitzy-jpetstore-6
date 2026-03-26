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

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * REST client component that encapsulates HTTP communication with the Account
 * Service microservice. This client replaces the monolith's in-process
 * {@code @SpringBean} injection of {@code AccountService} that was used by
 * {@code OrderActionBean} to verify user identity during order placement.
 *
 * <p>In the monolith, {@code OrderActionBean.newOrderForm()} (line 121) retrieved
 * the authenticated account from the HTTP session via:
 * <pre>{@code
 * AccountActionBean accountBean = (AccountActionBean)
 *     session.getAttribute("/actions/Account.action");
 * }</pre>
 * Similarly, {@code OrderActionBean.listOrders()} and {@code viewOrder()} read
 * the session-scoped {@code AccountActionBean} for the username.
 *
 * <p>In the microservices architecture, the Order Service verifies user existence
 * via a REST call to the Account Service:
 * <ul>
 *   <li><b>Endpoint:</b> {@code GET /api/accounts/{username}}</li>
 *   <li><b>Base URL:</b> {@code http://account-service:8081}
 *       (configurable via {@code services.account-service.url} in
 *       {@code application.yml})</li>
 *   <li><b>Called from:</b> {@code OrderService} and
 *       {@code OrderSagaOrchestrator} during order placement</li>
 * </ul>
 *
 * <p>The underlying {@link RestClient} bean is created in
 * {@link com.jpetstore.order.config.AppConfig#accountServiceRestClient(String)
 * AppConfig.accountServiceRestClient()} with a 5-second connection timeout
 * and 10-second read timeout. This client uses that pre-configured
 * {@code RestClient} — it does NOT construct its own.
 *
 * <p><b>Error Handling Strategy:</b> All error paths return
 * {@link Optional#empty()} rather than throwing exceptions to callers. This
 * supports the Saga pattern: if the Account Service is unavailable, the order
 * placement fails gracefully (fail-fast, no compensation needed since order
 * creation has not yet started). Specifically:
 * <ul>
 *   <li>HTTP 200 → {@code Optional.of(accountData)}</li>
 *   <li>HTTP 404 (user not found) → {@code Optional.empty()} with WARN log</li>
 *   <li>HTTP 4xx/5xx, connection/read timeout → {@code Optional.empty()} with
 *       ERROR log</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. The injected
 * {@code RestClient} is immutable and thread-safe. No mutable instance state
 * is maintained. No session state, no caching — account data is fetched fresh
 * on each call.
 *
 * <p><b>Cross-Service Communication Rule (AAP §0.8.1):</b> No service may
 * access another service's database directly — all cross-service data access
 * must go through the owning service's REST API. This client is the
 * <em>only</em> way the Order Service accesses account data.
 *
 * @see com.jpetstore.order.config.AppConfig#accountServiceRestClient(String)
 * @see com.jpetstore.order.service.OrderService
 * @see com.jpetstore.order.saga.OrderSagaOrchestrator
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    /**
     * Type reference for deserializing Account Service REST responses into
     * {@code Map<String, Object>}. Using a generic map avoids a compile-time
     * dependency on the account-service module's {@code AccountDTO} class,
     * maintaining clean service isolation. The map keys correspond to the JSON
     * property names returned by the Account Service (e.g., "username",
     * "firstName", "lastName", "email", etc.).
     */
    private static final ParameterizedTypeReference<Map<String, Object>> ACCOUNT_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    /**
     * Pre-configured REST client for Account Service communication.
     * Injected via constructor from the
     * {@link com.jpetstore.order.config.AppConfig#accountServiceRestClient(String)
     * accountServiceRestClient} bean defined in {@code AppConfig}.
     * Configured with base URL {@code http://account-service:8081} (from
     * {@code application.yml}), 5-second connect timeout, and 10-second read
     * timeout.
     */
    private final RestClient restClient;

    /**
     * Constructs the Account Service client with the specified pre-configured
     * {@link RestClient}.
     *
     * <p>The {@code @Qualifier("accountServiceRestClient")} annotation ensures
     * that the specific Account Service client bean is injected (rather than
     * the {@code catalogServiceRestClient} bean also defined in
     * {@code AppConfig}).
     *
     * @param restClient the pre-configured RestClient for Account Service
     *                   communication, injected from
     *                   {@code AppConfig.accountServiceRestClient()}
     */
    public AccountServiceClient(
            @Qualifier("accountServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Retrieves account data for the specified username from the Account Service.
     *
     * <p>This method replicates the monolith's account lookup pattern:
     * <ul>
     *   <li>Monolith: {@code AccountService.getAccount(String username)} calls
     *       {@code accountMapper.getAccountByUsername(username)} returning
     *       {@code Account} or {@code null}</li>
     *   <li>Microservice: sends {@code GET /api/accounts/{username}} to the
     *       Account Service, returning account data as a map or empty</li>
     * </ul>
     *
     * <p>The response is deserialized into a {@code Map<String, Object>} to
     * avoid a compile-time dependency on the account-service's
     * {@code AccountDTO} class. The map contains account fields such as
     * {@code username}, {@code firstName}, {@code lastName}, {@code email},
     * {@code favouriteCategoryId}, etc.
     *
     * <p><b>Error Handling:</b>
     * <ul>
     *   <li>HTTP 200 → parses response body, returns
     *       {@code Optional.of(accountData)}</li>
     *   <li>HTTP 404 → logs WARN, returns {@code Optional.empty()}
     *       (mirrors monolith's null return from
     *       {@code AccountMapper.getAccountByUsername()})</li>
     *   <li>Any other HTTP error (4xx, 5xx) or connection/read timeout →
     *       logs ERROR, returns {@code Optional.empty()} (graceful degradation
     *       per AAP §0.7.4 fallback pattern)</li>
     * </ul>
     *
     * @param username the username to look up; must not be {@code null}
     * @return an {@link Optional} containing the account data map if the
     *         account exists and the call succeeds, or
     *         {@link Optional#empty()} if the account is not found or an
     *         error occurs
     */
    public Optional<Map<String, Object>> getAccount(String username) {
        try {
            Map<String, Object> account = restClient.get()
                    .uri("/api/accounts/{username}", username)
                    .retrieve()
                    .body(ACCOUNT_TYPE_REF);
            return Optional.ofNullable(account);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Account not found for username: {}", username);
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("Error calling Account Service for username: {}", username, e);
            return Optional.empty();
        }
    }

    /**
     * Checks whether an account exists for the specified username.
     *
     * <p>This is a convenience method that delegates to
     * {@link #getAccount(String)} and checks whether a result was returned.
     * It is used by {@code OrderService} and {@code OrderSagaOrchestrator}
     * for pre-validation before order placement — replicating the monolith's
     * check in {@code OrderActionBean.newOrderForm()} where
     * {@code accountBean != null && accountBean.isAuthenticated()} was
     * evaluated before proceeding with order assembly.
     *
     * <p>Returns {@code false} for any error condition (account not found,
     * service unavailable, timeout) — supporting the fail-fast pattern in
     * the Saga orchestration. If the account cannot be verified, the order
     * should not proceed.
     *
     * @param username the username to check for existence
     * @return {@code true} if the account exists and the call succeeded,
     *         {@code false} otherwise (including on errors)
     */
    public boolean accountExists(String username) {
        return getAccount(username).isPresent();
    }
}
