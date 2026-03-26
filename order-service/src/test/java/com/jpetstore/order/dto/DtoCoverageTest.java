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
package com.jpetstore.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Order Service DTOs: {@link CartDTO}, {@link CartItemDTO},
 * {@link OrderDTO}, and {@link OrderRequest}.
 *
 * <p>Covers constructors, getters/setters, and toString methods to bring
 * coverage above the 80% instruction threshold.</p>
 */
class DtoCoverageTest {

    @Nested
    @DisplayName("CartDTO Tests")
    class CartDtoTests {

        @Test
        @DisplayName("Default constructor creates cart with empty items list")
        void shouldCreateEmptyCart() {
            CartDTO cart = new CartDTO();
            assertThat(cart.getId()).isNull();
            assertThat(cart.getItems()).isEmpty();
            assertThat(cart.getNumberOfItems()).isZero();
        }

        @Test
        @DisplayName("Setters populate all fields")
        void shouldPopulateAllFields() {
            CartDTO cart = new CartDTO();
            cart.setId("session-123");

            List<CartItemDTO> items = new ArrayList<>();
            CartItemDTO item = new CartItemDTO();
            item.setItemId("EST-1");
            item.setQuantity(3);
            item.setUnitPrice(new BigDecimal("16.50"));
            item.setTotal(new BigDecimal("49.50"));
            items.add(item);

            cart.setItems(items);
            cart.setSubTotal(new BigDecimal("49.50"));
            cart.setNumberOfItems(3);

            assertThat(cart.getId()).isEqualTo("session-123");
            assertThat(cart.getItems()).hasSize(1);
            assertThat(cart.getSubTotal()).isEqualByComparingTo("49.50");
            assertThat(cart.getNumberOfItems()).isEqualTo(3);
        }

        @Test
        @DisplayName("toString includes id and items")
        void shouldProduceReadableToString() {
            CartDTO cart = new CartDTO();
            cart.setId("sess-abc");
            cart.setItems(new ArrayList<>());
            cart.setSubTotal(BigDecimal.ZERO);
            cart.setNumberOfItems(0);

            String result = cart.toString();
            assertThat(result).isNotNull();
            assertThat(result).contains("sess-abc");
        }
    }

    @Nested
    @DisplayName("CartItemDTO Tests")
    class CartItemDtoTests {

        @Test
        @DisplayName("Default constructor creates empty item")
        void shouldCreateEmptyItem() {
            CartItemDTO item = new CartItemDTO();
            assertThat(item.getItemId()).isNull();
            assertThat(item.getQuantity()).isZero();
            assertThat(item.isInStock()).isFalse();
            assertThat(item.getUnitPrice()).isNull();
            assertThat(item.getTotal()).isNull();
        }

        @Test
        @DisplayName("Setters populate all fields")
        void shouldPopulateAllFields() {
            CartItemDTO item = new CartItemDTO();
            item.setItemId("EST-14");
            item.setQuantity(2);
            item.setInStock(true);
            item.setUnitPrice(new BigDecimal("16.50"));
            item.setTotal(new BigDecimal("33.00"));

            assertThat(item.getItemId()).isEqualTo("EST-14");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.isInStock()).isTrue();
            assertThat(item.getUnitPrice()).isEqualByComparingTo("16.50");
            assertThat(item.getTotal()).isEqualByComparingTo("33.00");
        }

        @Test
        @DisplayName("InStock boolean defaults to false and can be toggled")
        void shouldToggleInStock() {
            CartItemDTO item = new CartItemDTO();
            assertThat(item.isInStock()).isFalse();
            item.setInStock(true);
            assertThat(item.isInStock()).isTrue();
            item.setInStock(false);
            assertThat(item.isInStock()).isFalse();
        }
    }

    @Nested
    @DisplayName("OrderDTO Tests")
    class OrderDtoTests {

        @Test
        @DisplayName("Default constructor creates empty DTO")
        void shouldCreateEmpty() {
            OrderDTO dto = new OrderDTO();
            assertThat(dto.getOrderId()).isZero();
            assertThat(dto.getUsername()).isNull();
            assertThat(dto.getLineItems()).isNull();
        }

        @Test
        @DisplayName("Setters populate all fields")
        void shouldPopulateAllFields() {
            OrderDTO dto = new OrderDTO();
            LocalDateTime now = LocalDateTime.now();

            dto.setOrderId(1001);
            dto.setUsername("testuser");
            dto.setOrderDate(now);
            dto.setStatus("CONFIRMED");
            dto.setTotalPrice(new BigDecimal("125.50"));
            dto.setCreditCard("4111111111111111");
            dto.setExpiryDate("12/2028");
            dto.setCardType("Visa");
            dto.setCourier("UPS");
            dto.setLocale("US");

            dto.setShipToFirstName("John");
            dto.setShipToLastName("Doe");
            dto.setShipAddress1("123 Ship St");
            dto.setShipAddress2("Apt 1");
            dto.setShipCity("Shiptown");
            dto.setShipState("SS");
            dto.setShipZip("12345");
            dto.setShipCountry("USA");

            dto.setBillToFirstName("Jane");
            dto.setBillToLastName("Smith");
            dto.setBillAddress1("456 Bill Ave");
            dto.setBillAddress2("Suite 99");
            dto.setBillCity("Billburg");
            dto.setBillState("BB");
            dto.setBillZip("67890");
            dto.setBillCountry("USA");

            assertThat(dto.getOrderId()).isEqualTo(1001);
            assertThat(dto.getUsername()).isEqualTo("testuser");
            assertThat(dto.getOrderDate()).isEqualTo(now);
            assertThat(dto.getStatus()).isEqualTo("CONFIRMED");
            assertThat(dto.getTotalPrice()).isEqualByComparingTo("125.50");
            assertThat(dto.getCreditCard()).isEqualTo("4111111111111111");
            assertThat(dto.getExpiryDate()).isEqualTo("12/2028");
            assertThat(dto.getCardType()).isEqualTo("Visa");
            assertThat(dto.getCourier()).isEqualTo("UPS");
            assertThat(dto.getLocale()).isEqualTo("US");
            assertThat(dto.getShipToFirstName()).isEqualTo("John");
            assertThat(dto.getShipToLastName()).isEqualTo("Doe");
            assertThat(dto.getShipAddress1()).isEqualTo("123 Ship St");
            assertThat(dto.getShipAddress2()).isEqualTo("Apt 1");
            assertThat(dto.getShipCity()).isEqualTo("Shiptown");
            assertThat(dto.getShipState()).isEqualTo("SS");
            assertThat(dto.getShipZip()).isEqualTo("12345");
            assertThat(dto.getShipCountry()).isEqualTo("USA");
            assertThat(dto.getBillToFirstName()).isEqualTo("Jane");
            assertThat(dto.getBillToLastName()).isEqualTo("Smith");
            assertThat(dto.getBillAddress1()).isEqualTo("456 Bill Ave");
            assertThat(dto.getBillAddress2()).isEqualTo("Suite 99");
            assertThat(dto.getBillCity()).isEqualTo("Billburg");
            assertThat(dto.getBillState()).isEqualTo("BB");
            assertThat(dto.getBillZip()).isEqualTo("67890");
            assertThat(dto.getBillCountry()).isEqualTo("USA");
        }

        @Test
        @DisplayName("LineItemDetail inner class getters and setters")
        void shouldPopulateLineItemDetail() {
            OrderDTO.LineItemDetail detail = new OrderDTO.LineItemDetail();
            detail.setLineNumber(1);
            detail.setItemId("EST-1");
            detail.setQuantity(3);
            detail.setUnitPrice(new BigDecimal("16.50"));
            detail.setTotal(new BigDecimal("49.50"));

            assertThat(detail.getLineNumber()).isEqualTo(1);
            assertThat(detail.getItemId()).isEqualTo("EST-1");
            assertThat(detail.getQuantity()).isEqualTo(3);
            assertThat(detail.getUnitPrice()).isEqualByComparingTo("16.50");
            assertThat(detail.getTotal()).isEqualByComparingTo("49.50");
        }

        @Test
        @DisplayName("LineItems list can be set and retrieved")
        void shouldSetLineItemsList() {
            OrderDTO dto = new OrderDTO();
            List<OrderDTO.LineItemDetail> items = new ArrayList<>();
            OrderDTO.LineItemDetail detail = new OrderDTO.LineItemDetail();
            detail.setLineNumber(1);
            detail.setItemId("EST-2");
            items.add(detail);
            dto.setLineItems(items);

            assertThat(dto.getLineItems()).hasSize(1);
            assertThat(dto.getLineItems().get(0).getItemId()).isEqualTo("EST-2");
        }
    }

    @Nested
    @DisplayName("OrderRequest Tests")
    class OrderRequestTests {

        @Test
        @DisplayName("Default constructor creates empty request")
        void shouldCreateEmptyRequest() {
            OrderRequest request = new OrderRequest();
            assertThat(request.getUsername()).isNull();
        }

        @Test
        @DisplayName("Setters populate all fields")
        void shouldPopulateAllFields() {
            OrderRequest request = new OrderRequest();
            request.setUsername("testuser");
            request.setCartSessionId("sess-123");
            request.setCreditCard("4111111111111111");
            request.setExpiryDate("12/2028");
            request.setCardType("Visa");
            request.setCourier("UPS");
            request.setLocale("US");

            request.setShipToFirstName("John");
            request.setShipToLastName("Doe");
            request.setShipAddress1("123 Ship St");
            request.setShipAddress2("Apt 1");
            request.setShipCity("Shiptown");
            request.setShipState("SS");
            request.setShipZip("12345");
            request.setShipCountry("USA");

            request.setBillToFirstName("Jane");
            request.setBillToLastName("Smith");
            request.setBillAddress1("456 Bill Ave");
            request.setBillAddress2("Suite 99");
            request.setBillCity("Billburg");
            request.setBillState("BB");
            request.setBillZip("67890");
            request.setBillCountry("USA");

            assertThat(request.getUsername()).isEqualTo("testuser");
            assertThat(request.getCartSessionId()).isEqualTo("sess-123");
            assertThat(request.getCreditCard()).isEqualTo("4111111111111111");
            assertThat(request.getExpiryDate()).isEqualTo("12/2028");
            assertThat(request.getCardType()).isEqualTo("Visa");
            assertThat(request.getCourier()).isEqualTo("UPS");
            assertThat(request.getLocale()).isEqualTo("US");
            assertThat(request.getShipToFirstName()).isEqualTo("John");
            assertThat(request.getShipToLastName()).isEqualTo("Doe");
            assertThat(request.getShipAddress1()).isEqualTo("123 Ship St");
            assertThat(request.getShipAddress2()).isEqualTo("Apt 1");
            assertThat(request.getShipCity()).isEqualTo("Shiptown");
            assertThat(request.getShipState()).isEqualTo("SS");
            assertThat(request.getShipZip()).isEqualTo("12345");
            assertThat(request.getShipCountry()).isEqualTo("USA");
            assertThat(request.getBillToFirstName()).isEqualTo("Jane");
            assertThat(request.getBillToLastName()).isEqualTo("Smith");
            assertThat(request.getBillAddress1()).isEqualTo("456 Bill Ave");
            assertThat(request.getBillAddress2()).isEqualTo("Suite 99");
            assertThat(request.getBillCity()).isEqualTo("Billburg");
            assertThat(request.getBillState()).isEqualTo("BB");
            assertThat(request.getBillZip()).isEqualTo("67890");
            assertThat(request.getBillCountry()).isEqualTo("USA");
        }
    }
}
