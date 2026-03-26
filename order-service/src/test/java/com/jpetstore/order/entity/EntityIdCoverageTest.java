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
package com.jpetstore.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Order Service JPA entity inner ID classes and entity getters/setters.
 *
 * <p>Exercises constructors, getters/setters, equals/hashCode for
 * {@link OrderStatus.OrderStatusId}, {@link LineItem.LineItemId},
 * and additional coverage for {@link OrderStatus} and {@link Order} entities.</p>
 */
class EntityIdCoverageTest {

    @Nested
    @DisplayName("OrderStatus.OrderStatusId Tests")
    class OrderStatusIdTests {

        @Test
        @DisplayName("Default constructor creates empty ID")
        void shouldCreateEmptyId() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId();
            assertThat(id.getOrderId()).isZero();
            assertThat(id.getLineNum()).isZero();
        }

        @Test
        @DisplayName("Parameterized constructor sets fields")
        void shouldCreateWithParams() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId(1001, 1);
            assertThat(id.getOrderId()).isEqualTo(1001);
            assertThat(id.getLineNum()).isEqualTo(1);
        }

        @Test
        @DisplayName("Setters update fields")
        void shouldSetFields() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId();
            id.setOrderId(2001);
            id.setLineNum(5);
            assertThat(id.getOrderId()).isEqualTo(2001);
            assertThat(id.getLineNum()).isEqualTo(5);
        }

        @Test
        @DisplayName("Equals: same fields are equal")
        void shouldBeEqualWhenSameFields() {
            OrderStatus.OrderStatusId id1 = new OrderStatus.OrderStatusId(100, 1);
            OrderStatus.OrderStatusId id2 = new OrderStatus.OrderStatusId(100, 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        }

        @Test
        @DisplayName("Equals: different orderId are not equal")
        void shouldNotBeEqualDifferentOrderId() {
            OrderStatus.OrderStatusId id1 = new OrderStatus.OrderStatusId(100, 1);
            OrderStatus.OrderStatusId id2 = new OrderStatus.OrderStatusId(200, 1);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Equals: different lineNum are not equal")
        void shouldNotBeEqualDifferentLineNum() {
            OrderStatus.OrderStatusId id1 = new OrderStatus.OrderStatusId(100, 1);
            OrderStatus.OrderStatusId id2 = new OrderStatus.OrderStatusId(100, 2);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Equals: reflexive — equals self")
        void shouldEqualSelf() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId(100, 1);
            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("Equals: not equal to null")
        void shouldNotEqualNull() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId(100, 1);
            assertThat(id).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Equals: not equal to different class")
        void shouldNotEqualDifferentType() {
            OrderStatus.OrderStatusId id = new OrderStatus.OrderStatusId(100, 1);
            assertThat(id).isNotEqualTo("not an ID");
        }
    }

    @Nested
    @DisplayName("LineItem.LineItemId Tests")
    class LineItemIdTests {

        @Test
        @DisplayName("Default constructor creates empty ID")
        void shouldCreateEmptyId() {
            LineItem.LineItemId id = new LineItem.LineItemId();
            assertThat(id.getOrderId()).isZero();
            assertThat(id.getLineNum()).isZero();
        }

        @Test
        @DisplayName("Parameterized constructor sets fields")
        void shouldCreateWithParams() {
            LineItem.LineItemId id = new LineItem.LineItemId(1001, 3);
            assertThat(id.getOrderId()).isEqualTo(1001);
            assertThat(id.getLineNum()).isEqualTo(3);
        }

        @Test
        @DisplayName("Setters update fields")
        void shouldSetFields() {
            LineItem.LineItemId id = new LineItem.LineItemId();
            id.setOrderId(500);
            id.setLineNum(10);
            assertThat(id.getOrderId()).isEqualTo(500);
            assertThat(id.getLineNum()).isEqualTo(10);
        }

        @Test
        @DisplayName("Equals: same fields are equal")
        void shouldBeEqualWhenSameFields() {
            LineItem.LineItemId id1 = new LineItem.LineItemId(100, 1);
            LineItem.LineItemId id2 = new LineItem.LineItemId(100, 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        }

        @Test
        @DisplayName("Equals: different orderId are not equal")
        void shouldNotBeEqualDifferentOrderId() {
            LineItem.LineItemId id1 = new LineItem.LineItemId(100, 1);
            LineItem.LineItemId id2 = new LineItem.LineItemId(200, 1);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Equals: different lineNum are not equal")
        void shouldNotBeEqualDifferentLineNum() {
            LineItem.LineItemId id1 = new LineItem.LineItemId(100, 1);
            LineItem.LineItemId id2 = new LineItem.LineItemId(100, 2);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Equals: reflexive — equals self")
        void shouldEqualSelf() {
            LineItem.LineItemId id = new LineItem.LineItemId(100, 1);
            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("Equals: not equal to null")
        void shouldNotEqualNull() {
            LineItem.LineItemId id = new LineItem.LineItemId(100, 1);
            assertThat(id).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Equals: not equal to different class")
        void shouldNotEqualDifferentType() {
            LineItem.LineItemId id = new LineItem.LineItemId(100, 1);
            assertThat(id).isNotEqualTo("not an ID");
        }
    }

    @Nested
    @DisplayName("OrderStatus Entity Tests")
    class OrderStatusTests {

        @Test
        @DisplayName("Default constructor creates OrderStatus")
        void shouldCreateEmptyOrderStatus() {
            OrderStatus os = new OrderStatus();
            assertThat(os.getOrderId()).isZero();
            assertThat(os.getLineNum()).isZero();
            assertThat(os.getTimestamp()).isNull();
            assertThat(os.getStatus()).isNull();
        }

        @Test
        @DisplayName("Setters update all fields")
        void shouldSetAllFields() {
            OrderStatus os = new OrderStatus();
            LocalDateTime now = LocalDateTime.now();
            os.setOrderId(1001);
            os.setLineNum(1);
            os.setTimestamp(now);
            os.setStatus("P1");

            assertThat(os.getOrderId()).isEqualTo(1001);
            assertThat(os.getLineNum()).isEqualTo(1);
            assertThat(os.getTimestamp()).isEqualTo(now);
            assertThat(os.getStatus()).isEqualTo("P1");
        }
    }

    @Nested
    @DisplayName("LineItem Entity Tests")
    class LineItemEntityTests {

        @Test
        @DisplayName("Default constructor creates empty LineItem")
        void shouldCreateEmptyLineItem() {
            LineItem li = new LineItem();
            assertThat(li.getOrderId()).isZero();
            assertThat(li.getLineNum()).isZero();
            assertThat(li.getItemId()).isNull();
            assertThat(li.getQuantity()).isZero();
            assertThat(li.getUnitPrice()).isNull();
        }

        @Test
        @DisplayName("Setters populate all fields")
        void shouldPopulateAllFields() {
            LineItem li = new LineItem();
            li.setOrderId(1001);
            li.setLineNum(1);
            li.setItemId("EST-14");
            li.setQuantity(3);
            li.setUnitPrice(new BigDecimal("16.50"));

            assertThat(li.getOrderId()).isEqualTo(1001);
            assertThat(li.getLineNum()).isEqualTo(1);
            assertThat(li.getItemId()).isEqualTo("EST-14");
            assertThat(li.getQuantity()).isEqualTo(3);
            assertThat(li.getUnitPrice()).isEqualByComparingTo("16.50");
        }

        @Test
        @DisplayName("getTotal computes quantity * unitPrice")
        void shouldComputeTotal() {
            LineItem li = new LineItem();
            li.setQuantity(3);
            li.setUnitPrice(new BigDecimal("16.50"));

            BigDecimal total = li.getTotal();
            assertThat(total).isEqualByComparingTo("49.50");
        }

        @Test
        @DisplayName("getTotal returns null when unitPrice is null")
        void shouldReturnNullTotalWhenNoPriceSet() {
            LineItem li = new LineItem();
            li.setQuantity(3);
            // unitPrice is null
            assertThat(li.getTotal()).isNull();
        }
    }

    @Nested
    @DisplayName("Order Entity Additional Coverage")
    class OrderEntityTests {

        @Test
        @DisplayName("Default constructor creates empty Order")
        void shouldCreateEmptyOrder() {
            Order order = new Order();
            assertThat(order.getOrderId()).isZero();
            assertThat(order.getUsername()).isNull();
            assertThat(order.getOrderDate()).isNull();
        }

        @Test
        @DisplayName("Set and get shipping address fields")
        void shouldSetShippingFields() {
            Order order = new Order();
            order.setShipAddress1("123 Ship St");
            order.setShipAddress2("Apt 1");
            order.setShipCity("Shiptown");
            order.setShipState("SS");
            order.setShipZip("12345");
            order.setShipCountry("USA");
            order.setShipToFirstName("John");
            order.setShipToLastName("Doe");

            assertThat(order.getShipAddress1()).isEqualTo("123 Ship St");
            assertThat(order.getShipAddress2()).isEqualTo("Apt 1");
            assertThat(order.getShipCity()).isEqualTo("Shiptown");
            assertThat(order.getShipState()).isEqualTo("SS");
            assertThat(order.getShipZip()).isEqualTo("12345");
            assertThat(order.getShipCountry()).isEqualTo("USA");
            assertThat(order.getShipToFirstName()).isEqualTo("John");
            assertThat(order.getShipToLastName()).isEqualTo("Doe");
        }

        @Test
        @DisplayName("Set and get billing address fields")
        void shouldSetBillingFields() {
            Order order = new Order();
            order.setBillAddress1("456 Bill Ave");
            order.setBillAddress2("Suite 99");
            order.setBillCity("Billburg");
            order.setBillState("BB");
            order.setBillZip("67890");
            order.setBillCountry("USA");
            order.setBillToFirstName("Jane");
            order.setBillToLastName("Smith");

            assertThat(order.getBillAddress1()).isEqualTo("456 Bill Ave");
            assertThat(order.getBillAddress2()).isEqualTo("Suite 99");
            assertThat(order.getBillCity()).isEqualTo("Billburg");
            assertThat(order.getBillState()).isEqualTo("BB");
            assertThat(order.getBillZip()).isEqualTo("67890");
            assertThat(order.getBillCountry()).isEqualTo("USA");
            assertThat(order.getBillToFirstName()).isEqualTo("Jane");
            assertThat(order.getBillToLastName()).isEqualTo("Smith");
        }

        @Test
        @DisplayName("Set and get payment and order metadata")
        void shouldSetPaymentAndMetadata() {
            Order order = new Order();
            LocalDateTime now = LocalDateTime.now();
            order.setOrderId(1001);
            order.setUsername("testuser");
            order.setOrderDate(now);
            order.setCourier("UPS");
            order.setTotalPrice(new BigDecimal("125.50"));
            order.setCreditCard("4111111111111111");
            order.setExpiryDate("12/2028");
            order.setCardType("Visa");
            order.setLocale("US");
            order.setStatus("CONFIRMED");

            assertThat(order.getOrderId()).isEqualTo(1001);
            assertThat(order.getUsername()).isEqualTo("testuser");
            assertThat(order.getOrderDate()).isEqualTo(now);
            assertThat(order.getCourier()).isEqualTo("UPS");
            assertThat(order.getTotalPrice()).isEqualByComparingTo("125.50");
            assertThat(order.getCreditCard()).isEqualTo("4111111111111111");
            assertThat(order.getExpiryDate()).isEqualTo("12/2028");
            assertThat(order.getCardType()).isEqualTo("Visa");
            assertThat(order.getLocale()).isEqualTo("US");
            assertThat(order.getStatus()).isEqualTo("CONFIRMED");
        }
    }
}
