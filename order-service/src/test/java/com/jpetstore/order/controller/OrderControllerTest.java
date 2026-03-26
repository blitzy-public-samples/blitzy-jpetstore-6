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
package com.jpetstore.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.service.OrderService;

/**
 * {@code @WebMvcTest} unit tests for {@link OrderController}.
 *
 * <p>Validates HTTP request/response handling, HTTP status codes, Jakarta Bean
 * Validation, and DTO JSON serialization using MockMvc without starting a full
 * application context.</p>
 *
 * <p>The test covers all 3 REST endpoints exposed by {@link OrderController}:</p>
 * <ul>
 *   <li>{@code POST /api/orders} — Create order (6 tests: valid request → 201, 4 validation
 *       failures → 400, service error → 500)</li>
 *   <li>{@code GET /api/orders?username=} — List orders (3 tests: existing user → 200,
 *       unknown user → 200 empty list, missing param → 400)</li>
 *   <li>{@code GET /api/orders/{id}} — Get order (2 tests: existing → 200, not found → error)</li>
 * </ul>
 *
 * <p>Security filters are disabled via {@code @AutoConfigureMockMvc(addFilters = false)}
 * because authentication is handled by the API Gateway's JWT filter (per AAP §0.7.6),
 * not by the Order Service controller. This isolates the test to controller logic only.</p>
 *
 * <p>Models test patterns from the monolith's {@code OrderActionBeanTest} (constructor
 * defaults) and {@code OrderServiceTest} (Arrange/Act/Assert with Mockito mocks), adapted
 * for Spring Boot 3 {@code @WebMvcTest} conventions.</p>
 *
 * @author Blitzy Platform
 * @see OrderController
 * @see OrderService
 * @see OrderDTO
 * @see OrderRequest
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    /** HTTP request simulator — auto-configured by {@code @WebMvcTest}. */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson JSON serializer for request body construction in POST tests. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked OrderService — replaces the real service bean in the test application context
     * via {@code @MockitoBean} (Spring Boot 3.4+ replacement for {@code @MockBean}).
     * All three endpoint test groups mock this service's methods:
     * {@code insertOrder(OrderRequest)}, {@code getOrder(int)}, and
     * {@code getOrdersByUsername(String)}.
     */
    @MockitoBean
    private OrderService orderService;

    // =========================================================================
    // Test Group 1: POST /api/orders — Create Order
    // =========================================================================

    /**
     * Verifies that a valid order request returns HTTP 201 Created with a properly
     * serialized OrderDTO response body.
     *
     * <p>Mirrors the monolith's successful order placement flow from
     * {@code OrderActionBean.newOrder()} (lines 142-164) where a confirmed order
     * is submitted via {@code orderService.insertOrder(order)} and the user sees
     * the ViewOrder page.</p>
     */
    @Test
    void createOrder_WithValidRequest_Returns201Created() throws Exception {
        // Arrange: Build a valid OrderRequest with all 23 fields populated
        OrderRequest request = createValidOrderRequest();

        // Configure mock to return a confirmed order DTO
        OrderDTO expectedResponse = createSampleOrderDTO(1001);
        when(orderService.insertOrder(any(OrderRequest.class))).thenReturn(expectedResponse);

        // Act & Assert: POST request with JSON body, expect 201 Created
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId", is(1001)))
                .andExpect(jsonPath("$.username", is("j2ee")))
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.orderDate", notNullValue()))
                .andExpect(jsonPath("$.totalPrice").isNotEmpty());

        // Verify service was called exactly once
        verify(orderService).insertOrder(any(OrderRequest.class));
    }

    /**
     * Verifies that a missing/blank username triggers Jakarta Bean Validation
     * and returns HTTP 400 Bad Request without calling the service.
     *
     * <p>Tests the {@code @NotBlank} constraint on {@code OrderRequest.username}
     * which is triggered by {@code @Valid} on the controller's {@code @RequestBody}
     * parameter. The username field maps to the monolith's
     * {@code Order.initOrder()} line 288: username = account.getUsername().</p>
     */
    @Test
    void createOrder_WithMissingUsername_Returns400BadRequest() throws Exception {
        // Arrange: Build request with blank username to trigger @NotBlank validation
        OrderRequest request = createValidOrderRequest();
        request.setUsername("");

        // Act & Assert: POST should fail validation before reaching the service
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify service was NEVER called — validation short-circuited the request
        verify(orderService, never()).insertOrder(any());
    }

    /**
     * Verifies that a missing/blank cartSessionId triggers Jakarta Bean Validation
     * and returns HTTP 400 Bad Request.
     *
     * <p>The {@code cartSessionId} field references the externalized cart stored in
     * Redis (replacing the monolith's session-scoped {@code Cart} object). Without a
     * valid cart reference, the Order Service cannot retrieve cart contents.</p>
     */
    @Test
    void createOrder_WithMissingCartSessionId_Returns400BadRequest() throws Exception {
        // Arrange: Build request with blank cartSessionId
        OrderRequest request = createValidOrderRequest();
        request.setCartSessionId("");

        // Act & Assert: POST should fail validation
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify service was not called
        verify(orderService, never()).insertOrder(any());
    }

    /**
     * Verifies that missing shipping address fields trigger Jakarta Bean Validation
     * and return HTTP 400 Bad Request.
     *
     * <p>Tests the {@code @NotBlank} constraint on shipping address fields.
     * In the monolith, shipping address is populated from the Account's address
     * (Order.initOrder() lines 293-298) and may be modified on ShippingForm.jsp.</p>
     */
    @Test
    void createOrder_WithMissingShippingAddress_Returns400BadRequest() throws Exception {
        // Arrange: Build request with blank shipping city
        OrderRequest request = createValidOrderRequest();
        request.setShipCity("");

        // Act & Assert: POST should fail validation
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify service was not called
        verify(orderService, never()).insertOrder(any());
    }

    /**
     * Verifies that missing billing address fields trigger Jakarta Bean Validation
     * and return HTTP 400 Bad Request.
     *
     * <p>Tests the {@code @NotBlank} constraint on billing address fields.
     * In the monolith, billing address is populated from the Account's address
     * (Order.initOrder() lines 300-307).</p>
     */
    @Test
    void createOrder_WithMissingBillingAddress_Returns400BadRequest() throws Exception {
        // Arrange: Build request with blank billing address line 1
        OrderRequest request = createValidOrderRequest();
        request.setBillAddress1("");

        // Act & Assert: POST should fail validation
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // Verify service was not called
        verify(orderService, never()).insertOrder(any());
    }

    /**
     * Verifies that when the OrderService throws a RuntimeException (e.g., user not found,
     * cart empty, saga failure), the exception propagates through the controller.
     *
     * <p>Since the OrderController does not define a {@code @ExceptionHandler} or a global
     * {@code @ControllerAdvice}, unhandled RuntimeExceptions propagate as
     * {@link jakarta.servlet.ServletException} from MockMvc. This test verifies that the
     * original error cause is preserved.</p>
     *
     * <p>Models failure scenarios from the monolith's {@code OrderActionBean.newOrder()}
     * (lines 150-163) where errors are caught and forwarded to the Error.jsp page.</p>
     */
    @Test
    void createOrder_ServiceThrowsException_Returns500() throws Exception {
        // Arrange: Build a valid request but mock service to throw exception
        OrderRequest request = createValidOrderRequest();
        when(orderService.insertOrder(any(OrderRequest.class)))
                .thenThrow(new RuntimeException("User not found"));

        String requestJson = objectMapper.writeValueAsString(request);

        // Act & Assert: Without @ControllerAdvice, RuntimeException propagates as ServletException
        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)));

        // Verify the root cause is the expected RuntimeException with the correct message
        assertThat(ex.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCause().getMessage()).contains("User not found");
    }

    // =========================================================================
    // Test Group 2: GET /api/orders?username={username} — List Orders
    // =========================================================================

    /**
     * Verifies that listing orders for an existing user returns HTTP 200 OK
     * with a JSON array of OrderDTO objects.
     *
     * <p>Mirrors the monolith's {@code OrderActionBean.listOrders()} (lines 107-112)
     * which retrieves orders by username from the session-scoped AccountActionBean
     * and displays them on ListOrders.jsp.</p>
     */
    @Test
    void getOrdersByUsername_WithExistingUser_Returns200WithOrders() throws Exception {
        // Arrange: Create 2 sample orders for user "j2ee"
        OrderDTO order1 = createSampleOrderDTO(1001);
        OrderDTO order2 = createSampleOrderDTO(1002);
        List<OrderDTO> orders = Arrays.asList(order1, order2);

        when(orderService.getOrdersByUsername(eq("j2ee"))).thenReturn(orders);

        // Act & Assert: GET request with username parameter
        mockMvc.perform(get("/api/orders")
                        .param("username", "j2ee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].orderId", is(1001)))
                .andExpect(jsonPath("$[1].orderId", is(1002)))
                .andExpect(jsonPath("$[0].username", is("j2ee")))
                .andExpect(jsonPath("$[1].username", is("j2ee")));

        // Verify service was called with correct username
        verify(orderService).getOrdersByUsername(eq("j2ee"));
    }

    /**
     * Verifies that listing orders for an unknown user returns HTTP 200 OK
     * with an empty JSON array.
     *
     * <p>Models the monolith behavior where {@code OrderActionBean.listOrders()}
     * returns an empty list for users with no orders — the ListOrders.jsp page
     * still renders, showing "No orders found."</p>
     */
    @Test
    void getOrdersByUsername_WithUnknownUser_Returns200WithEmptyList() throws Exception {
        // Arrange: Return empty list for unknown user
        when(orderService.getOrdersByUsername(eq("unknown")))
                .thenReturn(Collections.emptyList());

        // Act & Assert: GET request returns 200 with empty JSON array
        mockMvc.perform(get("/api/orders")
                        .param("username", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Verify service was called
        verify(orderService).getOrdersByUsername(eq("unknown"));
    }

    /**
     * Verifies that omitting the required {@code username} query parameter
     * returns HTTP 400 Bad Request.
     *
     * <p>Spring MVC automatically returns 400 for missing required
     * {@code @RequestParam} parameters. The service should not be called.</p>
     */
    @Test
    void getOrdersByUsername_WithMissingUsername_Returns400() throws Exception {
        // Act & Assert: GET without username parameter should return 400
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isBadRequest());

        // Verify service was NOT called
        verify(orderService, never()).getOrdersByUsername(any());
    }

    // =========================================================================
    // Test Group 3: GET /api/orders/{id} — Get Order by ID
    // =========================================================================

    /**
     * Verifies that retrieving an existing order by ID returns HTTP 200 OK
     * with a fully populated OrderDTO including line items.
     *
     * <p>Mirrors the monolith's {@code OrderActionBean.viewOrder()} (lines 171-185)
     * combined with {@code OrderService.getOrder(int)} (lines 87-99) which loads
     * the order, its line items, and enriches each line item with catalog data.</p>
     */
    @Test
    void getOrder_WithExistingId_Returns200WithOrderDTO() throws Exception {
        // Arrange: Create a detailed sample order with line items
        OrderDTO expectedOrder = createSampleOrderDTO(1001);

        when(orderService.getOrder(eq(1001))).thenReturn(expectedOrder);

        // Act & Assert: GET by order ID
        mockMvc.perform(get("/api/orders/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId", is(1001)))
                .andExpect(jsonPath("$.username", is("j2ee")))
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.totalPrice", is(131.50)))
                .andExpect(jsonPath("$.lineItems", hasSize(2)))
                .andExpect(jsonPath("$.lineItems[0].itemId", is("EST-1")))
                .andExpect(jsonPath("$.lineItems[0].quantity", is(2)))
                .andExpect(jsonPath("$.lineItems[0].lineNumber", is(1)))
                .andExpect(jsonPath("$.lineItems[1].itemId", is("EST-14")))
                .andExpect(jsonPath("$.lineItems[1].quantity", is(1)))
                .andExpect(jsonPath("$.lineItems[1].lineNumber", is(2)));

        // Verify service was called with correct ID
        verify(orderService).getOrder(eq(1001));
    }

    /**
     * Verifies that retrieving a non-existent order results in an error.
     *
     * <p>When {@code OrderService.getOrder(9999)} throws a {@code RuntimeException}
     * ("Order not found: 9999"), the controller has no {@code @ExceptionHandler} or
     * {@code @ControllerAdvice} to map the exception to an HTTP status. The exception
     * propagates as a {@link jakarta.servlet.ServletException} in MockMvc.</p>
     *
     * <p>This models the monolith behavior where {@code OrderActionBean.viewOrder()}
     * (line 176) calls {@code orderService.getOrder()} and gets an exception for
     * non-existent orders, which is forwarded to the Error.jsp page.</p>
     */
    @Test
    void getOrder_WithNonExistentId_Returns404OrError() throws Exception {
        // Arrange: Mock service to throw exception for non-existent order
        when(orderService.getOrder(eq(9999)))
                .thenThrow(new RuntimeException("Order not found: 9999"));

        // Act & Assert: Without @ControllerAdvice, RuntimeException propagates as ServletException
        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/orders/9999")));

        // Verify the root cause is the expected RuntimeException with the correct message
        assertThat(ex.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCause().getMessage()).contains("Order not found: 9999");
    }

    // =========================================================================
    // Helper Methods — Test Data Factories
    // =========================================================================

    /**
     * Creates a valid {@link OrderRequest} with all 23 fields populated using
     * realistic monolith test data.
     *
     * <p>All {@code @NotBlank} fields are set to non-empty values to ensure
     * Jakarta Bean Validation passes. The values mirror the monolith's defaults
     * from {@code Order.initOrder()} (lines 288-322) and the test data from
     * {@code jpetstore-hsqldb-dataload.sql} for user "j2ee".</p>
     *
     * @return a fully populated OrderRequest ready for POST /api/orders
     */
    private OrderRequest createValidOrderRequest() {
        OrderRequest request = new OrderRequest();

        // User identity (Order.initOrder line 288)
        request.setUsername("j2ee");

        // Shipping address (Order.initOrder lines 291-298)
        request.setShipToFirstName("ABC");
        request.setShipToLastName("XYX");
        request.setShipAddress1("901 San Antonio Road");
        request.setShipAddress2("MS UCUP02-206");
        request.setShipCity("Palo Alto");
        request.setShipState("CA");
        request.setShipZip("94303");
        request.setShipCountry("USA");

        // Billing address (Order.initOrder lines 300-307)
        request.setBillToFirstName("ABC");
        request.setBillToLastName("XYX");
        request.setBillAddress1("901 San Antonio Road");
        request.setBillAddress2("MS UCUP02-206");
        request.setBillCity("Palo Alto");
        request.setBillState("CA");
        request.setBillZip("94303");
        request.setBillCountry("USA");

        // Payment info (Order.initOrder lines 311-313, monolith defaults)
        request.setCreditCard("999 9999 9999 9999");
        request.setExpiryDate("12/03");
        request.setCardType("Visa");

        // Courier and locale (Order.initOrder lines 314-315)
        request.setCourier("UPS");
        request.setLocale("CA");

        // Cart session reference (replaces monolith session-scoped Cart)
        request.setCartSessionId("session-test-123");

        return request;
    }

    /**
     * Creates a sample {@link OrderDTO} with realistic data for mock service responses.
     *
     * <p>The DTO includes core order fields, 2 sample {@link OrderDTO.LineItemDetail}
     * entries (EST-1 Angelfish and EST-14 Iguana), and a total price of $131.50.
     * All monetary values use {@link BigDecimal} matching the monolith's precision
     * requirements.</p>
     *
     * @param orderId the order identifier to set on the DTO
     * @return a fully populated OrderDTO ready for use as a mock return value
     */
    private OrderDTO createSampleOrderDTO(int orderId) {
        OrderDTO dto = new OrderDTO();

        // Core fields
        dto.setOrderId(orderId);
        dto.setUsername("j2ee");
        dto.setStatus("CONFIRMED");
        dto.setOrderDate(LocalDateTime.of(2025, 3, 26, 10, 30, 0));
        dto.setTotalPrice(new BigDecimal("131.50"));

        // Line items — 2 sample items matching catalog data
        OrderDTO.LineItemDetail item1 = new OrderDTO.LineItemDetail();
        item1.setLineNumber(1);
        item1.setItemId("EST-1");
        item1.setQuantity(2);
        item1.setUnitPrice(new BigDecimal("16.50"));
        item1.setTotal(new BigDecimal("33.00"));

        OrderDTO.LineItemDetail item2 = new OrderDTO.LineItemDetail();
        item2.setLineNumber(2);
        item2.setItemId("EST-14");
        item2.setQuantity(1);
        item2.setUnitPrice(new BigDecimal("98.50"));
        item2.setTotal(new BigDecimal("98.50"));

        dto.setLineItems(Arrays.asList(item1, item2));

        return dto;
    }
}
