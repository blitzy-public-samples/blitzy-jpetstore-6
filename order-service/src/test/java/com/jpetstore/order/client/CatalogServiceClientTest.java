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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
 * Unit tests for {@link CatalogServiceClient} — the Order Service's REST client
 * for communicating with the Catalog Service microservice.
 *
 * <p>This is the <b>most critical inter-service test class</b> in the Order Service
 * because it validates the inventory reservation and compensation paths that form the
 * core of the orchestration-based Saga pattern for distributed order transactions
 * (AAP Section 0.7.1).</p>
 *
 * <h3>Test Strategy</h3>
 * <p>Tests use a lightweight setup with {@link MockRestServiceServer} bound to a
 * manually-built {@link RestClient}, completely bypassing Spring dependency injection.
 * This ensures fast, isolated test execution without Spring context startup overhead.</p>
 *
 * <h3>Error Handling Classification Under Test</h3>
 * <table border="1">
 *   <tr><th>Method</th><th>HTTP 200</th><th>HTTP 404</th><th>HTTP 409</th><th>HTTP 5xx</th></tr>
 *   <tr><td>getItem()</td><td>Optional.of(data)</td><td>Optional.empty()</td><td>N/A</td><td>Optional.empty()</td></tr>
 *   <tr><td>decrementInventory()</td><td>true</td><td>N/A</td><td>false</td><td>THROWS RuntimeException</td></tr>
 *   <tr><td>restoreInventory()</td><td>true</td><td>N/A</td><td>N/A</td><td>false (NEVER throws)</td></tr>
 * </table>
 *
 * <p>The distinction between decrementInventory() throwing on 5xx and restoreInventory()
 * returning false on 5xx is the MOST CRITICAL aspect tested here. The Saga orchestrator
 * depends on this behavioral contract to correctly trigger compensation or log for
 * manual intervention.</p>
 *
 * @see CatalogServiceClient
 */
class CatalogServiceClientTest {

    /** Base URL used to construct the RestClient for Catalog Service communication. */
    private static final String CATALOG_SERVICE_BASE_URL = "http://catalog-service:8082";

    /** Mock HTTP server that intercepts outgoing REST calls from the CatalogServiceClient. */
    private MockRestServiceServer mockServer;

    /** The class under test — constructed directly with mock-backed RestClient. */
    private CatalogServiceClient client;

    /**
     * Sets up a fresh {@link MockRestServiceServer} and {@link CatalogServiceClient}
     * before each test method.
     *
     * <p>The setup mirrors production wiring: the CatalogServiceClient constructor
     * accepts a {@link RestClient} instance (in production, injected via
     * {@code @Qualifier("catalogServiceRestClient")}). Here we construct it manually
     * with MockRestServiceServer bound to the builder for request interception.</p>
     */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(CATALOG_SERVICE_BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new CatalogServiceClient(restClient);
    }

    // =========================================================================
    // getItem() Tests — Item Retrieval from Catalog Service
    // =========================================================================

    @Test
    @DisplayName("getItem: should return item data when item exists (HTTP 200)")
    void shouldReturnItemWhenItemExists() {
        // Arrange: Catalog Service returns item data for EST-1 (Angelfish)
        String itemJson = "{\"itemId\":\"EST-1\",\"productId\":\"FI-SW-01\","
                + "\"listPrice\":16.50,\"unitCost\":10.00,\"status\":\"P\","
                + "\"attribute1\":\"Large\",\"quantity\":10000}";

        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(itemJson, MediaType.APPLICATION_JSON));

        // Act: Call getItem with a valid item ID
        Optional<Map<String, Object>> result = client.getItem("EST-1");

        // Assert: Result is present and contains expected item data
        assertThat(result).isPresent();
        Map<String, Object> itemData = result.orElseThrow();
        assertThat(itemData.get("itemId")).isEqualTo("EST-1");
        assertThat(itemData.get("listPrice")).isNotNull();
        assertThat(itemData.get("productId")).isEqualTo("FI-SW-01");

        // Verify all expected HTTP interactions occurred
        mockServer.verify();
    }

    @Test
    @DisplayName("getItem: should return empty Optional when item not found (HTTP 404)")
    void shouldReturnEmptyOptionalWhenItemNotFound() {
        // Arrange: Catalog Service returns 404 for a non-existent item
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/NONEXISTENT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Act: Call getItem with a non-existent item ID
        Optional<Map<String, Object>> result = client.getItem("NONEXISTENT");

        // Assert: Result is empty, no exception thrown — clean 404 handling
        assertThat(result).isEmpty();

        // Verify the request was made
        mockServer.verify();
    }

    @Test
    @DisplayName("getItem: should return empty Optional when Catalog Service unavailable (HTTP 503)")
    void shouldReturnEmptyOptionalWhenCatalogServiceUnavailableForGetItem() {
        // Arrange: Catalog Service is down — returns 503 Service Unavailable
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act: Call getItem when Catalog Service is unavailable
        Optional<Map<String, Object>> result = client.getItem("EST-1");

        // Assert: Graceful degradation — empty Optional, no exception propagated
        assertThat(result).isEmpty();

        // Verify the request was attempted
        mockServer.verify();
    }

    // =========================================================================
    // decrementInventory() Tests — CRITICAL SAGA FORWARD STEP
    //
    // These tests validate the Saga Step 2 behavior: inventory reservation via
    // POST /api/items/{id}/inventory/decrement. The error handling classification
    // is critical for the Saga orchestrator's compensation decisions.
    // =========================================================================

    @Test
    @DisplayName("decrementInventory: should return true when decrement succeeds (HTTP 200)")
    void shouldReturnTrueWhenInventoryDecrementSucceeds() {
        // Arrange: Catalog Service successfully decrements inventory for EST-1
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess());

        // Act: Decrement inventory for 4 units of EST-1 in order-1001
        boolean result = client.decrementInventory("EST-1", 4, "order-1001");

        // Assert: Decrement succeeded
        assertThat(result).isTrue();

        // Verify the request was made
        mockServer.verify();
    }

    @Test
    @DisplayName("decrementInventory: should return false when insufficient stock (HTTP 409 Conflict)")
    void shouldReturnFalseWhenInsufficientStock() {
        // Arrange: Catalog Service returns 409 Conflict — insufficient stock
        // This is a DETERMINISTIC failure: no inventory was decremented,
        // so the Saga marks the order as FAILED without needing compensation.
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        // Act: Attempt to decrement more than available stock
        boolean result = client.decrementInventory("EST-1", 999, "order-1002");

        // Assert: Returns false (NOT an exception — clean business failure)
        assertThat(result).isFalse();

        // Verify the request was made
        mockServer.verify();
    }

    @Test
    @DisplayName("decrementInventory: should THROW RuntimeException when Catalog Service unavailable (HTTP 503)")
    void shouldThrowExceptionWhenCatalogServiceUnavailableForDecrement() {
        // Arrange: Catalog Service is down — returns 503 Service Unavailable
        // This is an INDETERMINATE failure: the Saga orchestrator must decide
        // whether to compensate or retry.
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act & Assert: MUST throw RuntimeException — the Saga orchestrator
        // catches this to trigger compensation logic
        assertThatThrownBy(() -> client.decrementInventory("EST-1", 4, "order-1003"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Catalog Service unavailable");

        // Verify the request was attempted
        mockServer.verify();
    }

    @Test
    @DisplayName("decrementInventory: should THROW RuntimeException on Internal Server Error (HTTP 500)")
    void shouldThrowExceptionOnInternalServerErrorForDecrement() {
        // Arrange: Catalog Service returns 500 Internal Server Error
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Act & Assert: 500 also triggers RuntimeException for Saga compensation
        assertThatThrownBy(() -> client.decrementInventory("EST-1", 4, "order-1003"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Catalog Service unavailable");

        // Verify the request was attempted
        mockServer.verify();
    }

    @Test
    @DisplayName("decrementInventory: should include orderId idempotency key in request body (AAP 0.7.1)")
    void shouldIncludeIdempotencyKeyInDecrementRequest() {
        // Arrange: Verify that the POST body contains the orderId as idempotency key.
        // Per AAP Section 0.7.1: "Every inventory reservation request includes the
        // orderId as an idempotency key. Catalog Service stores reservation records
        // indexed by orderId and returns success for duplicate requests without
        // double-decrementing."
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"orderId\"")))
                .andExpect(content().string(containsString("order-1004")))
                .andRespond(withSuccess());

        // Act: Decrement with specific orderId for idempotency
        boolean result = client.decrementInventory("EST-1", 4, "order-1004");

        // Assert: Request accepted
        assertThat(result).isTrue();

        // Verify: MockRestServiceServer confirms the request body contained the
        // idempotency key — this is the key assertion preventing double-decrements
        mockServer.verify();
    }

    @Test
    @DisplayName("decrementInventory: should send correct quantity in request body")
    void shouldSendCorrectQuantityInDecrementRequest() {
        // Arrange: Verify that the POST body includes the correct quantity field
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/decrement"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"quantity\"")))
                .andExpect(content().string(containsString("4")))
                .andRespond(withSuccess());

        // Act: Decrement 4 units
        boolean result = client.decrementInventory("EST-1", 4, "order-1005");

        // Assert: Request accepted with correct quantity
        assertThat(result).isTrue();

        // Verify: body contained the quantity
        mockServer.verify();
    }

    // =========================================================================
    // restoreInventory() Tests — SAGA COMPENSATING ACTION
    //
    // These tests validate the compensation behavior of the Saga pattern.
    // CRITICAL: restoreInventory() NEVER throws — it returns false on failure.
    // This is the opposite of decrementInventory() which THROWS on 5xx errors.
    // The InventoryCompensation class handles false returns by logging for
    // manual intervention.
    // =========================================================================

    @Test
    @DisplayName("restoreInventory: should return true when restore succeeds (HTTP 200)")
    void shouldReturnTrueWhenInventoryRestoreSucceeds() {
        // Arrange: Catalog Service successfully restores inventory for EST-1
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/restore"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess());

        // Act: Restore 4 units of EST-1 inventory for compensation
        boolean result = client.restoreInventory("EST-1", 4, "order-1006");

        // Assert: Restoration succeeded
        assertThat(result).isTrue();

        // Verify the request was made
        mockServer.verify();
    }

    @Test
    @DisplayName("restoreInventory: should return false (NOT throw) when Service Unavailable (HTTP 503)")
    void shouldReturnFalseWhenRestoreFailsDueToServiceUnavailable() {
        // Arrange: Catalog Service is unavailable during compensation attempt
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/restore"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act: Attempt to restore inventory when Catalog Service is down
        boolean result = client.restoreInventory("EST-1", 4, "order-1007");

        // Assert: Returns false — DOES NOT THROW (critical distinction from decrementInventory)
        // Compensation is best-effort; InventoryCompensation logs for manual intervention
        assertThat(result).isFalse();

        // Verify the request was attempted
        mockServer.verify();
    }

    @Test
    @DisplayName("restoreInventory: should return false (NOT throw) on Internal Server Error (HTTP 500)")
    void shouldReturnFalseWhenRestoreFailsDueToInternalServerError() {
        // Arrange: Catalog Service returns 500 during compensation attempt
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/restore"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Act: Attempt to restore inventory when server error occurs
        boolean result = client.restoreInventory("EST-1", 4, "order-1008");

        // Assert: Returns false — NEVER throws, even on 500 errors
        assertThat(result).isFalse();

        // Verify the request was attempted
        mockServer.verify();
    }

    @Test
    @DisplayName("restoreInventory: should include orderId idempotency key in request body")
    void shouldIncludeIdempotencyKeyInRestoreRequest() {
        // Arrange: Verify that the restore POST body also contains orderId for
        // idempotency — ensures restoration is applied exactly once per order
        mockServer.expect(requestTo(CATALOG_SERVICE_BASE_URL + "/api/items/EST-1/inventory/restore"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"orderId\"")))
                .andExpect(content().string(containsString("order-1009")))
                .andRespond(withSuccess());

        // Act: Restore with specific orderId for idempotency
        boolean result = client.restoreInventory("EST-1", 4, "order-1009");

        // Assert: Request accepted
        assertThat(result).isTrue();

        // Verify: MockRestServiceServer confirms orderId was present in body
        mockServer.verify();
    }
}
