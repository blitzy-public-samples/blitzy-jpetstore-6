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

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * JPA entity mapped to the {@code lineitem} table in the Order Service's PostgreSQL database.
 *
 * <p>Derived from the monolith's {@code org.mybatis.jpetstore.domain.LineItem} POJO but
 * transformed into a JPA entity with Jakarta Persistence annotations. Key differences from
 * the monolith version:</p>
 *
 * <ul>
 *   <li><strong>Cross-service FK removed</strong>: The monolith's {@code Item item} field
 *       (a direct reference to the Catalog domain object) is removed entirely. In the
 *       microservice architecture, Item lives in the Catalog Service's separate PostgreSQL
 *       database. The {@code itemId} field is retained as a plain String column — item
 *       details are fetched via {@code CatalogServiceClient} REST calls when needed.</li>
 *   <li><strong>Intra-service FK preserved</strong>: The {@code order_id} column references
 *       {@code orders.order_id} within the same database, managed by the {@code Order}
 *       entity's {@code @OneToMany @JoinColumn} relationship.</li>
 *   <li><strong>Composite primary key</strong>: Uses {@code @IdClass} with the
 *       {@link LineItemId} static inner class, combining {@code orderId} and
 *       {@code lineNum}.</li>
 *   <li><strong>Computed total</strong>: The {@code total} field is marked
 *       {@code @Transient} and computed as {@code unitPrice × quantity}, replacing the
 *       monolith's {@code calculateTotal()} which used {@code item.getListPrice()}.</li>
 *   <li><strong>No CartItem dependency</strong>: The monolith's constructor accepting
 *       {@code CartItem} is removed — conversion from cart items to line items is handled
 *       in the service/DTO layer.</li>
 * </ul>
 *
 * <p>PostgreSQL table definition (snake_case columns):</p>
 * <pre>{@code
 * CREATE TABLE lineitem (
 *     order_id   INTEGER      NOT NULL,
 *     line_num   INTEGER      NOT NULL,
 *     item_id    VARCHAR(10)  NOT NULL,
 *     quantity   INTEGER      NOT NULL,
 *     unit_price DECIMAL(10,2) NOT NULL,
 *     CONSTRAINT pk_lineitem PRIMARY KEY (order_id, line_num),
 *     CONSTRAINT fk_lineitem_order FOREIGN KEY (order_id) REFERENCES orders(order_id)
 * );
 * }</pre>
 *
 * @author Blitzy Platform
 * @see LineItemId
 */
@Entity
@Table(name = "lineitem")
@IdClass(LineItem.LineItemId.class)
public class LineItem implements Serializable {

    private static final long serialVersionUID = 6804536240033522156L;

    // -----------------------------------------------------------------------
    // Composite Primary Key Fields
    // -----------------------------------------------------------------------

    /**
     * Order identifier — part of composite PK.
     * Maps to {@code order_id} column (intra-service FK to {@code orders.order_id}).
     */
    @Id
    @Column(name = "order_id", nullable = false)
    private int orderId;

    /**
     * Line number within the order — part of composite PK.
     * Maps to {@code line_num} column. Monotonically increasing per order.
     */
    @Id
    @Column(name = "line_num", nullable = false)
    private int lineNum;

    // -----------------------------------------------------------------------
    // Persisted Data Fields
    // -----------------------------------------------------------------------

    /**
     * Item identifier referencing the Catalog Service's item table.
     * This is a plain String column — NO {@code @ManyToOne} relationship because the
     * item entity resides in a separate Catalog Service database. Cross-service foreign
     * keys are enforced at the application layer, not at the database constraint level.
     */
    @Column(name = "item_id", nullable = false, length = 10)
    private String itemId;

    /**
     * Quantity of items ordered on this line.
     */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * Unit price at the time the order was placed.
     * Precision matches the HSQLDB/PostgreSQL {@code DECIMAL(10,2)} column type.
     */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    // -----------------------------------------------------------------------
    // Transient Computed Field
    // -----------------------------------------------------------------------

    /**
     * Computed total for this line item ({@code unitPrice × quantity}).
     * Not persisted to the database — calculated on demand via {@link #getTotal()}.
     *
     * <p>In the monolith, {@code calculateTotal()} used {@code item.getListPrice()}.
     * In the microservice, we use the persisted {@code unitPrice} field instead,
     * avoiding cross-service calls while preserving identical business semantics.</p>
     */
    @Transient
    private BigDecimal total;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification for entity instantiation
     * by the persistence provider.
     */
    public LineItem() {
        // JPA requires a no-arg constructor
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the order identifier (part of composite PK).
     *
     * @return the order ID
     */
    public int getOrderId() {
        return orderId;
    }

    /**
     * Sets the order identifier (part of composite PK).
     *
     * @param orderId the order ID to set
     */
    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    /**
     * Returns the line number within the order (part of composite PK).
     *
     * @return the line number
     */
    public int getLineNum() {
        return lineNum;
    }

    /**
     * Sets the line number within the order (part of composite PK).
     *
     * @param lineNum the line number to set
     */
    public void setLineNum(int lineNum) {
        this.lineNum = lineNum;
    }

    /**
     * Returns the item identifier (cross-service reference to Catalog Service).
     *
     * @return the item ID
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets the item identifier (cross-service reference to Catalog Service).
     *
     * @param itemId the item ID to set
     */
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /**
     * Returns the quantity of items ordered on this line.
     *
     * @return the quantity
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * Sets the quantity of items ordered on this line.
     *
     * @param quantity the quantity to set
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /**
     * Returns the unit price at the time the order was placed.
     *
     * @return the unit price as a {@link BigDecimal}
     */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /**
     * Sets the unit price at the time the order was placed.
     *
     * @param unitPrice the unit price to set
     */
    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    /**
     * Computes and returns the total price for this line item as
     * {@code unitPrice × quantity}.
     *
     * <p>This replaces the monolith's {@code calculateTotal()} method, which computed
     * the total from {@code item.getListPrice() × quantity}. In the microservice, the
     * persisted {@code unitPrice} is used instead — it captures the price at the time
     * the order was placed, which is the correct value for order line totals.</p>
     *
     * @return the computed total, or {@code null} if {@code unitPrice} is {@code null}
     */
    public BigDecimal getTotal() {
        if (unitPrice != null) {
            total = unitPrice.multiply(new BigDecimal(quantity));
        } else {
            total = null;
        }
        return total;
    }

    // =======================================================================
    // Composite Primary Key Class
    // =======================================================================

    /**
     * Composite primary key class for the {@link LineItem} entity.
     *
     * <p>Combines {@code orderId} and {@code lineNum} to form the composite
     * primary key for the {@code lineitem} table. This class is referenced by the
     * {@code @IdClass} annotation on the {@code LineItem} entity.</p>
     *
     * <p>Per JPA specification requirements:</p>
     * <ul>
     *   <li>Implements {@link Serializable}</li>
     *   <li>Has a public no-arg constructor</li>
     *   <li>Field names match the {@code @Id} fields in the entity exactly</li>
     *   <li>Overrides {@link #equals(Object)} and {@link #hashCode()}</li>
     * </ul>
     */
    public static class LineItemId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Order identifier — must match the {@code orderId} field in {@link LineItem}.
         */
        private int orderId;

        /**
         * Line number — must match the {@code lineNum} field in {@link LineItem}.
         */
        private int lineNum;

        /**
         * No-arg constructor required by JPA for composite key instantiation.
         */
        public LineItemId() {
            // Required by JPA specification
        }

        /**
         * Parameterized constructor for programmatic composite key creation.
         *
         * @param orderId the order identifier
         * @param lineNum the line number within the order
         */
        public LineItemId(int orderId, int lineNum) {
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
         * Two {@code LineItemId} instances are equal if both {@code orderId}
         * and {@code lineNum} fields are equal.
         *
         * @param o the object to compare with
         * @return {@code true} if both fields match, {@code false} otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LineItemId that = (LineItemId) o;
            return Objects.equals(orderId, that.orderId)
                    && Objects.equals(lineNum, that.lineNum);
        }

        /**
         * Computes the hash code based on both composite key fields.
         *
         * @return the hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(orderId, lineNum);
        }
    }
}
