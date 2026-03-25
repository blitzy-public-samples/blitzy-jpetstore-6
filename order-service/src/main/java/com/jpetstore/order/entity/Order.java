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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * JPA entity mapped to the {@code orders} table in the Order Service's PostgreSQL
 * database ({@code jpetstore_order}).
 *
 * <p>This is the <strong>aggregate root</strong> of the Order bounded context. It
 * represents the complete order record including shipping address, billing address,
 * payment information, and associated line items. Derived from the monolith's
 * {@code org.mybatis.jpetstore.domain.Order} POJO but transformed into a JPA entity
 * with Jakarta Persistence annotations.</p>
 *
 * <h3>Key Differences from Monolith</h3>
 * <ul>
 *   <li><strong>ID generation</strong>: The monolith uses a non-thread-safe
 *       {@code sequence} table with a read-then-update pattern in
 *       {@code OrderService.getNextId()}. This entity uses PostgreSQL's native
 *       {@code order_id_seq} sequence via {@code @SequenceGenerator}, providing
 *       atomic, thread-safe ID generation (AAP §0.7.3).</li>
 *   <li><strong>Cross-service FK removed</strong>: The monolith's
 *       {@code orders.userid → account.userid} FK is removed. The {@code username}
 *       field is a plain String column — user existence is verified via the Account
 *       Service REST API at the application layer (AAP §0.8.1).</li>
 *   <li><strong>Saga status</strong>: A new {@code status} column (not in the
 *       monolith schema) supports the Saga orchestration state machine:
 *       PENDING → CONFIRMED / FAILED (AAP §0.7.1).</li>
 *   <li><strong>Date type</strong>: Uses {@link LocalDateTime} instead of
 *       {@code java.util.Date} for modern Java date-time handling.</li>
 *   <li><strong>initOrder() removed</strong>: The monolith's {@code initOrder(Account, Cart)}
 *       method that copies account + cart data into order fields is replaced by DTO-based
 *       construction in the service layer.</li>
 *   <li><strong>@OneToMany to LineItem</strong>: Line items are managed as a JPA
 *       collection with cascade operations, replacing the monolith's manual
 *       {@code lineItemMapper.insertLineItem()} calls.</li>
 * </ul>
 *
 * <h3>PostgreSQL Column Mapping (HSQLDB → PostgreSQL snake_case)</h3>
 * <table>
 *   <tr><th>HSQLDB Column</th><th>PostgreSQL Column</th><th>Java Field</th><th>Java Type</th></tr>
 *   <tr><td>orderid</td><td>order_id</td><td>orderId</td><td>int</td></tr>
 *   <tr><td>userid</td><td>userid</td><td>username</td><td>String</td></tr>
 *   <tr><td>orderdate</td><td>order_date</td><td>orderDate</td><td>LocalDateTime</td></tr>
 *   <tr><td>shipaddr1</td><td>ship_addr1</td><td>shipAddress1</td><td>String</td></tr>
 *   <tr><td>shipaddr2</td><td>ship_addr2</td><td>shipAddress2</td><td>String</td></tr>
 *   <tr><td>shipcity</td><td>ship_city</td><td>shipCity</td><td>String</td></tr>
 *   <tr><td>shipstate</td><td>ship_state</td><td>shipState</td><td>String</td></tr>
 *   <tr><td>shipzip</td><td>ship_zip</td><td>shipZip</td><td>String</td></tr>
 *   <tr><td>shipcountry</td><td>ship_country</td><td>shipCountry</td><td>String</td></tr>
 *   <tr><td>billaddr1</td><td>bill_addr1</td><td>billAddress1</td><td>String</td></tr>
 *   <tr><td>billaddr2</td><td>bill_addr2</td><td>billAddress2</td><td>String</td></tr>
 *   <tr><td>billcity</td><td>bill_city</td><td>billCity</td><td>String</td></tr>
 *   <tr><td>billstate</td><td>bill_state</td><td>billState</td><td>String</td></tr>
 *   <tr><td>billzip</td><td>bill_zip</td><td>billZip</td><td>String</td></tr>
 *   <tr><td>billcountry</td><td>bill_country</td><td>billCountry</td><td>String</td></tr>
 *   <tr><td>courier</td><td>courier</td><td>courier</td><td>String</td></tr>
 *   <tr><td>totalprice</td><td>total_price</td><td>totalPrice</td><td>BigDecimal</td></tr>
 *   <tr><td>billtofirstname</td><td>bill_to_first_name</td><td>billToFirstName</td><td>String</td></tr>
 *   <tr><td>billtolastname</td><td>bill_to_last_name</td><td>billToLastName</td><td>String</td></tr>
 *   <tr><td>shiptofirstname</td><td>ship_to_first_name</td><td>shipToFirstName</td><td>String</td></tr>
 *   <tr><td>shiptolastname</td><td>ship_to_last_name</td><td>shipToLastName</td><td>String</td></tr>
 *   <tr><td>creditcard</td><td>credit_card</td><td>creditCard</td><td>String</td></tr>
 *   <tr><td>exprdate</td><td>expr_date</td><td>expiryDate</td><td>String</td></tr>
 *   <tr><td>cardtype</td><td>card_type</td><td>cardType</td><td>String</td></tr>
 *   <tr><td>locale</td><td>locale</td><td>locale</td><td>String</td></tr>
 *   <tr><td>(NEW)</td><td>status</td><td>status</td><td>String</td></tr>
 * </table>
 *
 * @author Blitzy Platform
 * @see LineItem
 * @see com.jpetstore.order.saga.OrderSagaState
 */
@Entity
@Table(name = "orders")
public class Order implements Serializable {

    private static final long serialVersionUID = 8090561002392297381L;

    // -----------------------------------------------------------------------
    // Primary Key — PostgreSQL sequence-generated
    // -----------------------------------------------------------------------

    /**
     * Primary order identifier, generated by the PostgreSQL {@code order_id_seq}
     * sequence. Replaces the monolith's non-thread-safe {@code getNextId("ordernum")}
     * pattern (read-then-update on the {@code sequence} table).
     *
     * <p>The sequence starts at 2000 (max migrated ordernum 1000 + 1000 buffer)
     * to guarantee no collisions with legacy order IDs migrated from HSQLDB.</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_id_seq", allocationSize = 1)
    @Column(name = "order_id", nullable = false)
    private int orderId;

    // -----------------------------------------------------------------------
    // User Reference — cross-service FK removed
    // -----------------------------------------------------------------------

    /**
     * Username of the account that placed this order.
     *
     * <p>In the monolith, this was {@code orders.userid} with a FK to
     * {@code account.userid}. In the microservice architecture, this is a plain
     * String column — the cross-service FK is removed per AAP §0.8.1.
     * User existence is verified at the application layer via REST API call
     * to Account Service ({@code GET /api/accounts/{username}}).</p>
     *
     * <p>The PostgreSQL column retains the original HSQLDB column name
     * {@code userid} to maintain consistency with the data migration mapping.
     * The Java field is named {@code username} to match the monolith's domain
     * model convention.</p>
     */
    @Column(name = "userid", nullable = false, length = 80)
    private String username;

    // -----------------------------------------------------------------------
    // Order Date
    // -----------------------------------------------------------------------

    /**
     * Timestamp when the order was created.
     *
     * <p>Uses {@link LocalDateTime} instead of the monolith's {@code java.util.Date}
     * for modern Java date-time handling. Maps to PostgreSQL
     * {@code TIMESTAMP WITH TIME ZONE} column via Hibernate 6.x.</p>
     */
    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    // -----------------------------------------------------------------------
    // Shipping Address Fields
    // -----------------------------------------------------------------------

    /** Shipping address line 1. Maps to {@code ship_addr1} column. */
    @Column(name = "ship_addr1", nullable = false, length = 80)
    private String shipAddress1;

    /** Shipping address line 2 (nullable). Maps to {@code ship_addr2} column. */
    @Column(name = "ship_addr2", length = 80)
    private String shipAddress2;

    /** Shipping city. Maps to {@code ship_city} column. */
    @Column(name = "ship_city", nullable = false, length = 80)
    private String shipCity;

    /** Shipping state/province. Maps to {@code ship_state} column. */
    @Column(name = "ship_state", nullable = false, length = 80)
    private String shipState;

    /** Shipping postal/zip code. Maps to {@code ship_zip} column. */
    @Column(name = "ship_zip", nullable = false, length = 20)
    private String shipZip;

    /** Shipping country. Maps to {@code ship_country} column. */
    @Column(name = "ship_country", nullable = false, length = 20)
    private String shipCountry;

    // -----------------------------------------------------------------------
    // Billing Address Fields
    // -----------------------------------------------------------------------

    /** Billing address line 1. Maps to {@code bill_addr1} column. */
    @Column(name = "bill_addr1", nullable = false, length = 80)
    private String billAddress1;

    /** Billing address line 2 (nullable). Maps to {@code bill_addr2} column. */
    @Column(name = "bill_addr2", length = 80)
    private String billAddress2;

    /** Billing city. Maps to {@code bill_city} column. */
    @Column(name = "bill_city", nullable = false, length = 80)
    private String billCity;

    /** Billing state/province. Maps to {@code bill_state} column. */
    @Column(name = "bill_state", nullable = false, length = 80)
    private String billState;

    /** Billing postal/zip code. Maps to {@code bill_zip} column. */
    @Column(name = "bill_zip", nullable = false, length = 20)
    private String billZip;

    /** Billing country. Maps to {@code bill_country} column. */
    @Column(name = "bill_country", nullable = false, length = 20)
    private String billCountry;

    // -----------------------------------------------------------------------
    // Order Details
    // -----------------------------------------------------------------------

    /** Shipping courier (e.g., "UPS"). Maps to {@code courier} column. */
    @Column(name = "courier", nullable = false, length = 80)
    private String courier;

    /**
     * Total price of the order.
     *
     * <p>Uses {@link BigDecimal} for precise monetary arithmetic (never double
     * or float), matching the monolith's pattern. Precision and scale match the
     * PostgreSQL {@code NUMERIC(10,2)} column type.</p>
     */
    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    // -----------------------------------------------------------------------
    // Name Fields
    // -----------------------------------------------------------------------

    /** Billing recipient first name. Maps to {@code bill_to_first_name} column. */
    @Column(name = "bill_to_first_name", nullable = false, length = 80)
    private String billToFirstName;

    /** Billing recipient last name. Maps to {@code bill_to_last_name} column. */
    @Column(name = "bill_to_last_name", nullable = false, length = 80)
    private String billToLastName;

    /** Shipping recipient first name. Maps to {@code ship_to_first_name} column. */
    @Column(name = "ship_to_first_name", nullable = false, length = 80)
    private String shipToFirstName;

    /** Shipping recipient last name. Maps to {@code ship_to_last_name} column. */
    @Column(name = "ship_to_last_name", nullable = false, length = 80)
    private String shipToLastName;

    // -----------------------------------------------------------------------
    // Payment Fields
    // -----------------------------------------------------------------------

    /** Credit card number (e.g., "999 9999 9999 9999"). Maps to {@code credit_card}. */
    @Column(name = "credit_card", nullable = false, length = 80)
    private String creditCard;

    /**
     * Credit card expiry date as a string (e.g., "12/03").
     * Maps to {@code expr_date} column (max length 7 per schema).
     */
    @Column(name = "expr_date", nullable = false, length = 7)
    private String expiryDate;

    /**
     * Credit card type. Values: "Visa", "MasterCard", "American Express".
     * Maps to {@code card_type} column.
     *
     * <p>The simulated payment logic (hardcoded credit card types) is preserved
     * exactly as implemented in the monolith per AAP §0.8.1.</p>
     */
    @Column(name = "card_type", nullable = false, length = 80)
    private String cardType;

    // -----------------------------------------------------------------------
    // Miscellaneous Fields
    // -----------------------------------------------------------------------

    /** Locale code (e.g., "CA"). Maps to {@code locale} column. */
    @Column(name = "locale", nullable = false, length = 80)
    private String locale;

    /**
     * Order status for Saga orchestration state machine.
     *
     * <p>This column is NEW — not present in the monolith's HSQLDB schema.
     * Values: "PENDING", "CONFIRMED", "FAILED".
     * Nullable for backward compatibility during data migration — migrated orders
     * from HSQLDB will have {@code null} status since the monolith does not
     * track order status in this way. In the monolith, {@code Order.status} was
     * set to "P" in {@code initOrder()} — the microservice uses full-length
     * descriptive status strings for clarity.</p>
     *
     * @see com.jpetstore.order.saga.OrderSagaState
     */
    @Column(name = "status", length = 20)
    private String status;

    // -----------------------------------------------------------------------
    // Line Items — @OneToMany relationship (intra-service)
    // -----------------------------------------------------------------------

    /**
     * Line items associated with this order.
     *
     * <p>Managed as a JPA collection with {@code CascadeType.ALL} — when an order
     * is persisted, its line items are automatically persisted as well. This replaces
     * the monolith's manual loop in {@code OrderService.insertOrder()} where each
     * line item is individually inserted via {@code lineItemMapper.insertLineItem()}.
     * The {@code @JoinColumn} establishes the intra-service FK from
     * {@code lineitem.order_id} to {@code orders.order_id}.</p>
     *
     * <p>{@code FetchType.LAZY} is used to avoid loading line items when only order
     * header data is needed (e.g., order list queries). Line items are loaded on demand
     * when explicitly accessed.</p>
     *
     * <p>{@code orphanRemoval = true} ensures that if a line item is removed from
     * this collection, the corresponding database row is deleted.</p>
     */
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id")
    @OrderBy("lineNum ASC")
    private List<LineItem> lineItems = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification for entity instantiation
     * by the persistence provider.
     */
    public Order() {
        // JPA requires a no-arg constructor
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the order identifier (primary key).
     *
     * @return the order ID generated by PostgreSQL sequence
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
     * Returns the username of the account that placed this order.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the username of the account that placed this order.
     *
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the date and time when the order was created.
     *
     * @return the order date as {@link LocalDateTime}
     */
    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    /**
     * Sets the date and time when the order was created.
     *
     * @param orderDate the order date to set
     */
    public void setOrderDate(LocalDateTime orderDate) {
        this.orderDate = orderDate;
    }

    /**
     * Returns shipping address line 1.
     *
     * @return the shipping address line 1
     */
    public String getShipAddress1() {
        return shipAddress1;
    }

    /**
     * Sets shipping address line 1.
     *
     * @param shipAddress1 the shipping address line 1 to set
     */
    public void setShipAddress1(String shipAddress1) {
        this.shipAddress1 = shipAddress1;
    }

    /**
     * Returns shipping address line 2.
     *
     * @return the shipping address line 2 (may be null)
     */
    public String getShipAddress2() {
        return shipAddress2;
    }

    /**
     * Sets shipping address line 2.
     *
     * @param shipAddress2 the shipping address line 2 to set
     */
    public void setShipAddress2(String shipAddress2) {
        this.shipAddress2 = shipAddress2;
    }

    /**
     * Returns the shipping city.
     *
     * @return the shipping city
     */
    public String getShipCity() {
        return shipCity;
    }

    /**
     * Sets the shipping city.
     *
     * @param shipCity the shipping city to set
     */
    public void setShipCity(String shipCity) {
        this.shipCity = shipCity;
    }

    /**
     * Returns the shipping state/province.
     *
     * @return the shipping state
     */
    public String getShipState() {
        return shipState;
    }

    /**
     * Sets the shipping state/province.
     *
     * @param shipState the shipping state to set
     */
    public void setShipState(String shipState) {
        this.shipState = shipState;
    }

    /**
     * Returns the shipping postal/zip code.
     *
     * @return the shipping zip code
     */
    public String getShipZip() {
        return shipZip;
    }

    /**
     * Sets the shipping postal/zip code.
     *
     * @param shipZip the shipping zip code to set
     */
    public void setShipZip(String shipZip) {
        this.shipZip = shipZip;
    }

    /**
     * Returns the shipping country.
     *
     * @return the shipping country
     */
    public String getShipCountry() {
        return shipCountry;
    }

    /**
     * Sets the shipping country.
     *
     * @param shipCountry the shipping country to set
     */
    public void setShipCountry(String shipCountry) {
        this.shipCountry = shipCountry;
    }

    /**
     * Returns billing address line 1.
     *
     * @return the billing address line 1
     */
    public String getBillAddress1() {
        return billAddress1;
    }

    /**
     * Sets billing address line 1.
     *
     * @param billAddress1 the billing address line 1 to set
     */
    public void setBillAddress1(String billAddress1) {
        this.billAddress1 = billAddress1;
    }

    /**
     * Returns billing address line 2.
     *
     * @return the billing address line 2 (may be null)
     */
    public String getBillAddress2() {
        return billAddress2;
    }

    /**
     * Sets billing address line 2.
     *
     * @param billAddress2 the billing address line 2 to set
     */
    public void setBillAddress2(String billAddress2) {
        this.billAddress2 = billAddress2;
    }

    /**
     * Returns the billing city.
     *
     * @return the billing city
     */
    public String getBillCity() {
        return billCity;
    }

    /**
     * Sets the billing city.
     *
     * @param billCity the billing city to set
     */
    public void setBillCity(String billCity) {
        this.billCity = billCity;
    }

    /**
     * Returns the billing state/province.
     *
     * @return the billing state
     */
    public String getBillState() {
        return billState;
    }

    /**
     * Sets the billing state/province.
     *
     * @param billState the billing state to set
     */
    public void setBillState(String billState) {
        this.billState = billState;
    }

    /**
     * Returns the billing postal/zip code.
     *
     * @return the billing zip code
     */
    public String getBillZip() {
        return billZip;
    }

    /**
     * Sets the billing postal/zip code.
     *
     * @param billZip the billing zip code to set
     */
    public void setBillZip(String billZip) {
        this.billZip = billZip;
    }

    /**
     * Returns the billing country.
     *
     * @return the billing country
     */
    public String getBillCountry() {
        return billCountry;
    }

    /**
     * Sets the billing country.
     *
     * @param billCountry the billing country to set
     */
    public void setBillCountry(String billCountry) {
        this.billCountry = billCountry;
    }

    /**
     * Returns the shipping courier.
     *
     * @return the courier name
     */
    public String getCourier() {
        return courier;
    }

    /**
     * Sets the shipping courier.
     *
     * @param courier the courier name to set
     */
    public void setCourier(String courier) {
        this.courier = courier;
    }

    /**
     * Returns the total price of the order.
     *
     * @return the total price as {@link BigDecimal}
     */
    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    /**
     * Sets the total price of the order.
     *
     * @param totalPrice the total price to set
     */
    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    /**
     * Returns the billing recipient first name.
     *
     * @return the bill-to first name
     */
    public String getBillToFirstName() {
        return billToFirstName;
    }

    /**
     * Sets the billing recipient first name.
     *
     * @param billToFirstName the bill-to first name to set
     */
    public void setBillToFirstName(String billToFirstName) {
        this.billToFirstName = billToFirstName;
    }

    /**
     * Returns the billing recipient last name.
     *
     * @return the bill-to last name
     */
    public String getBillToLastName() {
        return billToLastName;
    }

    /**
     * Sets the billing recipient last name.
     *
     * @param billToLastName the bill-to last name to set
     */
    public void setBillToLastName(String billToLastName) {
        this.billToLastName = billToLastName;
    }

    /**
     * Returns the shipping recipient first name.
     *
     * @return the ship-to first name
     */
    public String getShipToFirstName() {
        return shipToFirstName;
    }

    /**
     * Sets the shipping recipient first name.
     *
     * @param shipToFirstName the ship-to first name to set
     */
    public void setShipToFirstName(String shipToFirstName) {
        this.shipToFirstName = shipToFirstName;
    }

    /**
     * Returns the shipping recipient last name.
     *
     * @return the ship-to last name
     */
    public String getShipToLastName() {
        return shipToLastName;
    }

    /**
     * Sets the shipping recipient last name.
     *
     * @param shipToLastName the ship-to last name to set
     */
    public void setShipToLastName(String shipToLastName) {
        this.shipToLastName = shipToLastName;
    }

    /**
     * Returns the credit card number.
     *
     * @return the credit card number string
     */
    public String getCreditCard() {
        return creditCard;
    }

    /**
     * Sets the credit card number.
     *
     * @param creditCard the credit card number to set
     */
    public void setCreditCard(String creditCard) {
        this.creditCard = creditCard;
    }

    /**
     * Returns the credit card expiry date.
     *
     * @return the expiry date string (e.g., "12/03")
     */
    public String getExpiryDate() {
        return expiryDate;
    }

    /**
     * Sets the credit card expiry date.
     *
     * @param expiryDate the expiry date string to set
     */
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    /**
     * Returns the credit card type.
     *
     * @return the card type (e.g., "Visa", "MasterCard", "American Express")
     */
    public String getCardType() {
        return cardType;
    }

    /**
     * Sets the credit card type.
     *
     * @param cardType the card type to set
     */
    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    /**
     * Returns the locale code.
     *
     * @return the locale code (e.g., "CA")
     */
    public String getLocale() {
        return locale;
    }

    /**
     * Sets the locale code.
     *
     * @param locale the locale code to set
     */
    public void setLocale(String locale) {
        this.locale = locale;
    }

    /**
     * Returns the order status for Saga orchestration.
     *
     * @return the status string ("PENDING", "CONFIRMED", "FAILED"), or {@code null} for migrated orders
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the order status for Saga orchestration.
     *
     * @param status the status string to set ("PENDING", "CONFIRMED", "FAILED")
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the list of line items associated with this order.
     *
     * @return the line items (never {@code null}; may be empty)
     */
    public List<LineItem> getLineItems() {
        return lineItems;
    }

    /**
     * Sets the list of line items associated with this order.
     *
     * @param lineItems the line items to set
     */
    public void setLineItems(List<LineItem> lineItems) {
        this.lineItems = lineItems;
    }

    // -----------------------------------------------------------------------
    // Convenience Methods
    // -----------------------------------------------------------------------

    /**
     * Adds a line item to this order's line item collection.
     *
     * <p>This convenience method provides a simple way to add individual line
     * items to the order. It replaces the monolith's {@code addLineItem(LineItem)}
     * method (line 331 in monolith Order.java). The monolith's overloaded
     * {@code addLineItem(CartItem)} method is NOT included — CartItem conversion
     * to LineItem is handled in the service layer via DTO mapping.</p>
     *
     * <p>Because the {@code lineItems} collection is annotated with
     * {@code @OneToMany(cascade = CascadeType.ALL)}, any line item added via
     * this method will be automatically persisted when the order is saved.</p>
     *
     * @param lineItem the line item to add to this order (must not be {@code null})
     */
    public void addLineItem(LineItem lineItem) {
        if (lineItem != null) {
            lineItems.add(lineItem);
        }
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Two orders are equal if they have the same {@code orderId}.
     *
     * <p>Uses the natural key (orderId) for equality, consistent with the
     * JPA entity identity contract. For transient entities (orderId == 0),
     * identity-based equality applies.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same order
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Order order = (Order) o;
        return orderId == order.orderId;
    }

    /**
     * Hash code based on {@code orderId}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(orderId);
    }

    /**
     * Returns a string representation of this order for debugging and logging.
     *
     * @return a string containing the order's key fields
     */
    @Override
    public String toString() {
        return "Order{"
                + "orderId=" + orderId
                + ", username='" + username + '\''
                + ", orderDate=" + orderDate
                + ", totalPrice=" + totalPrice
                + ", status='" + status + '\''
                + ", lineItems=" + (lineItems != null ? lineItems.size() : 0)
                + '}';
    }
}
