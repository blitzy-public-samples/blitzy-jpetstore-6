/*
 * Copyright 2010-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jpetstore.order.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA entity mapped to the {@code orderstatus} table in the Order Service's
 * PostgreSQL database ({@code jpetstore_order}).
 *
 * <p>This entity captures the status of each line within an order. The composite
 * primary key ({@code order_id}, {@code line_num}) is modeled using the
 * {@link IdClass} strategy with the static inner class {@link OrderStatusId}.
 *
 * <h3>Column Mapping (HSQLDB &rarr; PostgreSQL snake_case)</h3>
 * <table>
 *   <tr><th>HSQLDB Column</th><th>PostgreSQL Column</th><th>Java Field</th><th>Java Type</th></tr>
 *   <tr><td>orderid</td><td>order_id</td><td>orderId</td><td>int</td></tr>
 *   <tr><td>linenum</td><td>line_num</td><td>lineNum</td><td>int</td></tr>
 *   <tr><td>timestamp</td><td>timestamp</td><td>timestamp</td><td>LocalDateTime</td></tr>
 *   <tr><td>status</td><td>status</td><td>status</td><td>String</td></tr>
 * </table>
 *
 * <p>The {@code order_id} field is an intra-service logical foreign key referencing
 * {@code orders.order_id} within the same database; the constraint is enforced at the
 * database level via Liquibase rather than through a {@code @ManyToOne} JPA mapping.
 *
 * <p>This is a pure data entity with no business logic, consistent with the AAP
 * requirement that all domain rules remain unchanged from the monolith.
 *
 * @see OrderStatusId
 */
@Entity
@Table(name = "orderstatus")
@IdClass(OrderStatus.OrderStatusId.class)
public class OrderStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    // ──────────────────────────────────────────────────────────────────────────
    // Composite Primary Key Fields
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Order identifier — part of the composite primary key.
     * Logically references {@code orders.order_id} (intra-service FK enforced at DB level).
     */
    @Id
    @Column(name = "order_id", nullable = false)
    private int orderId;

    /**
     * Line number within the order — part of the composite primary key.
     */
    @Id
    @Column(name = "line_num", nullable = false)
    private int lineNum;

    // ──────────────────────────────────────────────────────────────────────────
    // Non-Key Fields
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Timestamp recording when this status entry was created or last updated.
     * Mapped to PostgreSQL {@code TIMESTAMP} column. Uses {@link LocalDateTime}
     * for modern Java date-time handling with Hibernate 6.x.
     */
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /**
     * Status code for this order line (e.g., {@code "P"} for pending).
     * Maximum length of 2 characters per the schema definition.
     */
    @Column(name = "status", nullable = false, length = 2)
    private String status;

    // ──────────────────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Default no-arg constructor required by JPA specification.
     */
    public OrderStatus() {
        // JPA requires a no-arg constructor
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the order identifier.
     *
     * @return the order ID
     */
    public int getOrderId() {
        return orderId;
    }

    /**
     * Sets the order identifier.
     *
     * @param orderId the order ID to set
     */
    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    /**
     * Returns the line number within the order.
     *
     * @return the line number
     */
    public int getLineNum() {
        return lineNum;
    }

    /**
     * Sets the line number within the order.
     *
     * @param lineNum the line number to set
     */
    public void setLineNum(int lineNum) {
        this.lineNum = lineNum;
    }

    /**
     * Returns the timestamp of this status entry.
     *
     * @return the timestamp as {@link LocalDateTime}
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the timestamp of this status entry.
     *
     * @param timestamp the timestamp to set
     */
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Returns the status code for this order line.
     *
     * @return the status string (max 2 characters)
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the status code for this order line.
     *
     * @param status the status string to set (max 2 characters)
     */
    public void setStatus(String status) {
        this.status = status;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Composite Primary Key ID Class
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Composite primary key class for {@link OrderStatus}.
     *
     * <p>Per the JPA specification, an {@code @IdClass} must:
     * <ul>
     *   <li>Implement {@link Serializable}</li>
     *   <li>Have a public no-arg constructor</li>
     *   <li>Override {@link #equals(Object)} and {@link #hashCode()}</li>
     *   <li>Declare fields matching the {@code @Id} fields of the entity</li>
     * </ul>
     */
    public static class OrderStatusId implements Serializable {

        private static final long serialVersionUID = 1L;

        private int orderId;
        private int lineNum;

        /**
         * Default no-arg constructor required by JPA specification for ID classes.
         */
        public OrderStatusId() {
            // JPA requires a no-arg constructor
        }

        /**
         * Convenience constructor for creating a composite key instance.
         *
         * @param orderId the order identifier
         * @param lineNum the line number within the order
         */
        public OrderStatusId(int orderId, int lineNum) {
            this.orderId = orderId;
            this.lineNum = lineNum;
        }

        /**
         * Returns the order identifier.
         *
         * @return the order ID
         */
        public int getOrderId() {
            return orderId;
        }

        /**
         * Sets the order identifier.
         *
         * @param orderId the order ID to set
         */
        public void setOrderId(int orderId) {
            this.orderId = orderId;
        }

        /**
         * Returns the line number.
         *
         * @return the line number
         */
        public int getLineNum() {
            return lineNum;
        }

        /**
         * Sets the line number.
         *
         * @param lineNum the line number to set
         */
        public void setLineNum(int lineNum) {
            this.lineNum = lineNum;
        }

        /**
         * Compares this composite key with another object for equality.
         * Two {@code OrderStatusId} instances are equal if both {@code orderId}
         * and {@code lineNum} fields match.
         *
         * @param o the object to compare with
         * @return {@code true} if the objects are equal, {@code false} otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            OrderStatusId that = (OrderStatusId) o;
            return Objects.equals(orderId, that.orderId)
                    && Objects.equals(lineNum, that.lineNum);
        }

        /**
         * Returns a hash code based on both composite key fields.
         *
         * @return the hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(orderId, lineNum);
        }
    }
}
