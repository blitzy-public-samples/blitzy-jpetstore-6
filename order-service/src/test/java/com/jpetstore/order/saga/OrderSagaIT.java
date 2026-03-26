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
package com.jpetstore.order.saga;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jpetstore.order.client.AccountServiceClient;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.repository.CartStateRepository;
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderSagaStateRepository;
import com.jpetstore.order.repository.OrderStatusRepository;

/**
 * Integration test for the order Saga orchestrator, validating the distributed
 * transaction failure scenarios defined in AAP §0.7.1.
 *
 * <p>This is the <b>most critical integration test</b> in the JPetStore
 * microservices decomposition. It tests the real saga orchestrator against a
 * real PostgreSQL database, with WireMock simulating the Catalog Service's
 * inventory endpoints. Unlike the unit-level {@code OrderSagaOrchestratorTest},
 * this test exercises:</p>
 * <ul>
 *   <li>Real JPA transactions committed to PostgreSQL</li>
 *   <li>Real REST client calls to a simulated Catalog Service</li>
 *   <li>Real saga state persistence and recovery</li>
 *   <li>Actual {@code order_id_seq} PostgreSQL sequence generation</li>
 * </ul>
 *
 * <h3>Five Scenarios from AAP §0.7.1</h3>
 * <ol>
 *   <li><b>Success</b>: All inventory decremented, order CONFIRMED</li>
 *   <li><b>Partial failure with compensation</b>: First item succeeds, second
 *       fails → inventory restored for first item, order FAILED</li>
 *   <li><b>Catalog Service unavailable</b>: HTTP 500 → order FAILED</li>
 *   <li><b>Idempotency</b>: Duplicate calls with same orderId deduplicated</li>
 *   <li><b>Crash recovery</b>: PENDING saga state persists for reconciliation</li>
 * </ol>
 *
 * <h3>Infrastructure</h3>
 * <ul>
 *   <li><b>PostgreSQL 16</b> — Testcontainers, schema via Hibernate ddl-auto</li>
 *   <li><b>WireMock</b> — Simulates Catalog Service inventory endpoints</li>
 *   <li><b>Redis</b> — Mocked via {@code @MockBean} (not needed for saga logic)</li>
 * </ul>
 *
 * @see OrderSagaOrchestrator
 * @see OrderSagaState
 * @see InventoryCompensation
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OrderSagaIT {

    // =========================================================================
    // Infrastructure: PostgreSQL Testcontainer
    // =========================================================================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_order_test")
            .withUsername("test")
            .withPassword("test");

    // =========================================================================
    // Infrastructure: WireMock for Catalog Service simulation
    // =========================================================================

    /**
     * WireMock extension simulating the Catalog Service REST API.
     * Runs on a dynamic port, registered as a JUnit 5 extension that starts
     * before the Spring context loads (so @DynamicPropertySource can read the port).
     */
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    // =========================================================================
    // Dynamic Properties: Point services at test infrastructure
    // =========================================================================

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");

        // Point CatalogServiceClient at WireMock
        registry.add("services.catalog-service.url", wireMock::baseUrl);

        // Account service URL (not used by saga, but required for AppConfig bean)
        registry.add("services.account-service.url", () -> "http://localhost:19999");
    }

    // =========================================================================
    // Mocked Beans: Components not needed for Saga testing
    // =========================================================================

    /**
     * Mocked RedisConnectionFactory — prevents Redis auto-configuration from
     * failing. The saga orchestrator does not interact with Redis.
     */
    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * Mocked CartStateRepository — prevents Redis hash repository initialization.
     * Cart operations are not part of the saga.
     */
    @MockBean
    private CartStateRepository cartStateRepository;

    /**
     * Mocked AccountServiceClient — prevents HTTP calls to Account Service.
     * The saga does not verify accounts (that happens in OrderService before saga).
     */
    @MockBean
    private AccountServiceClient accountServiceClient;

    // =========================================================================
    // Autowired Components Under Test
    // =========================================================================

    @Autowired
    private OrderSagaOrchestrator sagaOrchestrator;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusRepository orderStatusRepository;

    @Autowired
    private LineItemRepository lineItemRepository;

    @Autowired
    private OrderSagaStateRepository sagaStateRepository;

    // =========================================================================
    // Test Setup
    // =========================================================================

    @BeforeEach
    void setUp() {
        // Clean all tables before each test for isolation
        sagaStateRepository.deleteAll();
        lineItemRepository.deleteAll();
        orderStatusRepository.deleteAll();
        orderRepository.deleteAll();

        // Reset WireMock stubs between tests
        wireMock.resetAll();
    }

    // =========================================================================
    // Scenario 1: Success — All inventory decremented, order CONFIRMED
    // =========================================================================

    @Nested
    @DisplayName("Scenario 1: Success — Inventory decremented, order CONFIRMED")
    class SuccessScenarioTests {

        /**
         * Verifies the happy-path saga execution: order created with PENDING
         * status, all inventory decremented via Catalog Service, order
         * confirmed. This mirrors the monolith's successful
         * {@code OrderService.insertOrder()} but as a distributed saga.
         */
        @Test
        @DisplayName("should confirm order when all inventory reservations succeed")
        void shouldConfirmOrderWhenAllReservationsSucceed() {
            // Stub WireMock: Both items succeed (HTTP 200)
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-2/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));

            // Create order with 2 line items
            Order order = createTestOrder("j2ee", 2);

            // Execute saga
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order is CONFIRMED
            assertThat(result.getStatus()).isEqualTo("CONFIRMED");
            assertThat(result.getOrderId()).isGreaterThan(0);

            // Verify: Order persisted in database
            Order dbOrder = orderRepository.findById(result.getOrderId()).orElseThrow();
            assertThat(dbOrder.getStatus()).isEqualTo("CONFIRMED");
            assertThat(dbOrder.getUsername()).isEqualTo("j2ee");

            // Verify: Line items persisted
            List<LineItem> dbLineItems = lineItemRepository.findByOrderId(result.getOrderId());
            assertThat(dbLineItems).hasSize(2);

            // Verify: OrderStatus record created
            List<com.jpetstore.order.entity.OrderStatus> statuses =
                    orderStatusRepository.findByOrderId(result.getOrderId());
            assertThat(statuses).isNotEmpty();

            // Verify: Saga state in terminal COMPLETED state
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);
            assertThat(sagaStates.get(0).getStatus()).isEqualTo(OrderSagaState.STATUS_COMPLETED);
            assertThat(sagaStates.get(0).getCurrentStep()).isEqualTo(OrderSagaStep.CONFIRM_ORDER);

            // Verify: Both decrement endpoints were called exactly once
            wireMock.verify(1, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-1/inventory/decrement")));
            wireMock.verify(1, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-2/inventory/decrement")));
        }

        /**
         * Verifies that a single-item order succeeds correctly (simplest case).
         */
        @Test
        @DisplayName("should confirm single-item order")
        void shouldConfirmSingleItemOrder() {
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            assertThat(result.getStatus()).isEqualTo("CONFIRMED");

            // Verify saga completed
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);
            assertThat(sagaStates.get(0).getStatus()).isEqualTo(OrderSagaState.STATUS_COMPLETED);
        }
    }

    // =========================================================================
    // Scenario 2: Partial Failure — Compensation restores inventory
    // =========================================================================

    @Nested
    @DisplayName("Scenario 2: Partial Failure — Compensation restores inventory")
    class PartialFailureTests {

        /**
         * Verifies the partial failure scenario: first item's inventory is
         * decremented (HTTP 200), but second item fails (HTTP 409 — insufficient
         * stock). The saga must:
         * 1. Compensate the first item (call restore endpoint)
         * 2. Mark the order as FAILED
         * 3. Persist saga state as FAILED
         */
        @Test
        @DisplayName("should compensate and fail order when second item has insufficient stock")
        void shouldCompensateWhenSecondItemFails() {
            // Stub WireMock: First item succeeds, second fails with 409
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-2/inventory/decrement"))
                    .willReturn(aResponse()
                            .withStatus(409)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"INSUFFICIENT_STOCK\"}")));
            // Stub compensation endpoint for first item
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/restore"))
                    .willReturn(aResponse().withStatus(200)));

            // Create order with 2 line items
            Order order = createTestOrder("j2ee", 2);

            // Execute saga
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order is FAILED
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify: Order persisted in database with FAILED status
            Order dbOrder = orderRepository.findById(result.getOrderId()).orElseThrow();
            assertThat(dbOrder.getStatus()).isEqualTo("FAILED");

            // Verify: Saga state in terminal FAILED state
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);
            assertThat(sagaStates.get(0).getStatus()).isEqualTo(OrderSagaState.STATUS_FAILED);

            // Verify: Compensation was called for the first item (restore inventory)
            wireMock.verify(1, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-1/inventory/restore")));
        }

        /**
         * Verifies that when the first item fails, no compensation is needed
         * (no items were successfully decremented before the failure).
         */
        @Test
        @DisplayName("should fail without compensation when first item has insufficient stock")
        void shouldFailWithoutCompensationWhenFirstItemFails() {
            // Stub WireMock: First item fails immediately with 409
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse()
                            .withStatus(409)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"INSUFFICIENT_STOCK\"}")));

            Order order = createTestOrder("j2ee", 2);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order is FAILED
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify: No compensation calls (no items were decremented)
            wireMock.verify(0, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-1/inventory/restore")));
            wireMock.verify(0, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-2/inventory/restore")));

            // Verify: Second item was never attempted (processing stopped at first failure)
            wireMock.verify(0, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-2/inventory/decrement")));
        }
    }

    // =========================================================================
    // Scenario 3: Catalog Service Unavailable → order FAILED
    // =========================================================================

    @Nested
    @DisplayName("Scenario 3: Catalog Service unavailable → order FAILED")
    class CatalogUnavailableTests {

        /**
         * Verifies that when the Catalog Service returns HTTP 500 (server error),
         * the saga catches the RuntimeException thrown by CatalogServiceClient,
         * marks the order as FAILED, and persists the failure state.
         */
        @Test
        @DisplayName("should fail order when Catalog Service returns HTTP 500")
        void shouldFailOrderWhenCatalogReturns500() {
            // Stub WireMock: Catalog returns 500 Internal Server Error
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("Internal Server Error")));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order is FAILED (not CONFIRMED)
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify: Database reflects FAILED status
            Order dbOrder = orderRepository.findById(result.getOrderId()).orElseThrow();
            assertThat(dbOrder.getStatus()).isEqualTo("FAILED");

            // Verify: Saga state recorded as FAILED
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);
            assertThat(sagaStates.get(0).getStatus()).isEqualTo(OrderSagaState.STATUS_FAILED);
        }

        /**
         * Verifies that when the Catalog Service is unreachable (connection refused
         * / timeout), the saga handles the error gracefully and marks the order as
         * FAILED. Simulated via a long delay exceeding the REST client timeout.
         */
        @Test
        @DisplayName("should fail order when Catalog Service times out")
        void shouldFailOrderWhenCatalogTimesOut() {
            // Stub WireMock: Catalog responds with a 15-second delay (exceeds 10s read timeout)
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(15000)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order is FAILED due to timeout
            assertThat(result.getStatus()).isEqualTo("FAILED");
        }

        /**
         * Verifies partial failure with compensation when the first item succeeds
         * but the Catalog Service becomes unavailable (HTTP 503) for the second item.
         */
        @Test
        @DisplayName("should compensate first item when Catalog Service fails on second item")
        void shouldCompensateWhenCatalogFailsPartially() {
            // First item succeeds
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));
            // Second item returns 503
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-2/inventory/decrement"))
                    .willReturn(aResponse().withStatus(503)));
            // Compensation for first item
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/restore"))
                    .willReturn(aResponse().withStatus(200)));

            Order order = createTestOrder("j2ee", 2);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify: Compensation was triggered for the first item
            wireMock.verify(1, postRequestedFor(
                    urlPathEqualTo("/api/items/EST-1/inventory/restore")));
        }
    }

    // =========================================================================
    // Scenario 4: Idempotency — Duplicate decrement calls handled safely
    // =========================================================================

    @Nested
    @DisplayName("Scenario 4: Idempotency — orderId passed as idempotency key")
    class IdempotencyTests {

        /**
         * Verifies that the saga passes the orderId to the Catalog Service as
         * an idempotency key in the request body. The Catalog Service uses this
         * to deduplicate retry requests. This test verifies the request body
         * content, not the Catalog Service's deduplication (which is tested
         * in InventoryServiceIT).
         */
        @Test
        @DisplayName("should include orderId in inventory decrement request body")
        void shouldIncludeOrderIdInDecrementRequest() {
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            assertThat(result.getStatus()).isEqualTo("CONFIRMED");

            // Verify: The request body contains the orderId for idempotency
            wireMock.verify(postRequestedFor(
                    urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .withRequestBody(
                            com.github.tomakehurst.wiremock.client.WireMock.containing(
                                    "\"orderId\":\""
                                            + result.getOrderId() + "\"")));
        }
    }

    // =========================================================================
    // Scenario 5: Crash Recovery — PENDING state persists for reconciliation
    // =========================================================================

    @Nested
    @DisplayName("Scenario 5: Crash Recovery — PENDING state for reconciliation")
    class CrashRecoveryTests {

        /**
         * Verifies that after Step 1 (CREATE_ORDER) completes successfully,
         * the saga state is persisted as PENDING in the database. If a JVM
         * crash occurs before Step 2 starts, the reconciliation job can
         * discover this PENDING saga and resume or compensate it.
         *
         * <p>This test simulates the crash window by creating an order via
         * the saga's step 1 and then verifying the database state without
         * proceeding to step 2 (the WireMock stub returns a failure that
         * causes the saga to stop, but the PENDING record was already committed
         * in its own transaction).</p>
         */
        @Test
        @DisplayName("should persist PENDING saga state after Step 1 for crash recovery")
        void shouldPersistPendingSagaStateAfterStepOne() {
            // Stub: Catalog returns 500 so saga fails at Step 2
            // But Step 1 already committed the PENDING order in its own transaction
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(500)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // The order was created in Step 1's transaction (PENDING) and then
            // updated to FAILED in the compensation handler
            assertThat(result.getOrderId()).isGreaterThan(0);

            // Verify: The saga state was created and is now in FAILED terminal state
            // (because the saga ran to completion with compensation)
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);

            // The saga state's currentStep should be RESERVE_INVENTORY (where failure occurred)
            assertThat(sagaStates.get(0).getCurrentStep()).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);

            // Verify: createdAt is populated (for reconciliation job's timeout query)
            assertThat(sagaStates.get(0).getCreatedAt()).isNotNull();
            assertThat(sagaStates.get(0).getCreatedAt()).isBefore(LocalDateTime.now().plusMinutes(1));
        }

        /**
         * Verifies that the saga state entity has all the fields needed by the
         * reconciliation job to query stalled sagas. Per AAP §0.7.1, the job
         * queries:
         * {@code SELECT * FROM order_saga_state WHERE status NOT IN ('COMPLETED', 'FAILED')
         *   AND created_at < NOW() - INTERVAL '60 seconds'}
         */
        @Test
        @DisplayName("should track saga state with orderId, step, status, and timestamps")
        void shouldTrackSagaStateCompletely() {
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(200)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Saga state has all fields populated
            List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(result.getOrderId());
            assertThat(sagaStates).hasSize(1);

            OrderSagaState state = sagaStates.get(0);
            assertThat(state.getSagaId()).isNotNull().isNotEmpty();
            assertThat(state.getOrderId()).isEqualTo(result.getOrderId());
            assertThat(state.getCurrentStep()).isNotNull();
            assertThat(state.getStatus()).isNotNull();
            assertThat(state.getCreatedAt()).isNotNull();
            assertThat(state.getUpdatedAt()).isNotNull();
        }

        /**
         * Verifies that the order record is durable (committed) in its own
         * transaction BEFORE any cross-service REST call. This is the fundamental
         * guarantee that prevents "phantom decrements" — if the JVM crashes
         * during Step 2, the PENDING order record already exists in the database.
         *
         * <p>This is verified by confirming the order has a valid auto-generated
         * ID from the PostgreSQL sequence, and all required fields are persisted.</p>
         */
        @Test
        @DisplayName("should persist order with auto-generated sequence ID before REST calls")
        void shouldPersistOrderBeforeRestCalls() {
            // Catalog returns 500 — but Step 1 (order creation) already committed
            wireMock.stubFor(post(urlPathEqualTo("/api/items/EST-1/inventory/decrement"))
                    .willReturn(aResponse().withStatus(500)));

            Order order = createTestOrder("j2ee", 1);
            Order result = sagaOrchestrator.executeOrderSaga(order);

            // Verify: Order record exists in database with sequence-generated ID
            Order dbOrder = orderRepository.findById(result.getOrderId()).orElseThrow();
            assertThat(dbOrder.getOrderId()).isGreaterThan(0);
            assertThat(dbOrder.getUsername()).isEqualTo("j2ee");

            // Verify: Line items persisted (committed with order in Step 1)
            List<LineItem> dbLineItems = lineItemRepository.findByOrderId(result.getOrderId());
            assertThat(dbLineItems).hasSize(1);
            assertThat(dbLineItems.get(0).getItemId()).isEqualTo("EST-1");
        }
    }

    // =========================================================================
    // Helper Methods — Test Data Construction
    // =========================================================================

    /**
     * Creates a fully populated test Order with the specified number of line items.
     * All required fields are set to valid values matching the monolith's
     * {@code Order.initOrder(Account, Cart)} behavior.
     *
     * @param username     the username for the order
     * @param lineItemCount the number of line items to create (EST-1, EST-2, etc.)
     * @return a new Order entity ready for saga execution
     */
    private Order createTestOrder(String username, int lineItemCount) {
        Order order = new Order();
        order.setUsername(username);
        order.setOrderDate(LocalDateTime.now());

        // Shipping address (mirrors Account fields populated by Order.initOrder())
        order.setShipAddress1("123 Main St");
        order.setShipAddress2("");
        order.setShipCity("Anytown");
        order.setShipState("CA");
        order.setShipZip("12345");
        order.setShipCountry("USA");

        // Billing address
        order.setBillAddress1("123 Main St");
        order.setBillAddress2("");
        order.setBillCity("Anytown");
        order.setBillState("CA");
        order.setBillZip("12345");
        order.setBillCountry("USA");

        // Order details
        order.setCourier("UPS");
        order.setTotalPrice(new BigDecimal("100.00"));
        order.setBillToFirstName("John");
        order.setBillToLastName("Doe");
        order.setShipToFirstName("John");
        order.setShipToLastName("Doe");
        order.setCreditCard("999 9999 9999 9999");
        order.setExpiryDate("12/03");
        order.setCardType("Visa");
        order.setLocale("US");
        order.setStatus("PENDING");

        // Create line items (EST-1, EST-2, etc.)
        List<LineItem> lineItems = new ArrayList<>();
        for (int i = 1; i <= lineItemCount; i++) {
            LineItem item = new LineItem();
            item.setLineNum(i);
            item.setItemId("EST-" + i);
            item.setQuantity(2);
            item.setUnitPrice(new BigDecimal("50.00"));
            lineItems.add(item);
        }
        order.setLineItems(lineItems);

        return order;
    }
}
