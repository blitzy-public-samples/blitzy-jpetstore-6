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

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;

/**
 * Order creation request DTO for the {@code POST /api/orders} endpoint.
 *
 * <p>This DTO replicates the data flow from the monolith's
 * {@code Order.initOrder(Account, Cart)} method, where order data is assembled
 * from the authenticated user's account details and the externalized cart state.
 * It carries the shipping address (which the user may modify on the shipping form),
 * the billing address (initially copied from the account), payment information
 * (credit card, expiry date, card type — with monolith-compatible defaults),
 * courier, locale, and a reference to the externalized cart stored in Redis.</p>
 *
 * <h3>Field Mapping to Monolith {@code Order.initOrder()}</h3>
 * <ul>
 *   <li>Fields 1–9 (username, shipTo*): Sourced from {@code Account} fields (lines 288–298 of Order.java)</li>
 *   <li>Fields 10–17 (billTo*): Sourced from {@code Account} fields (lines 300–307 of Order.java)</li>
 *   <li>Fields 18–22 (creditCard, expiryDate, cardType, courier, locale): Defaults from Order.java lines 311–315</li>
 *   <li>Field 23 (cartSessionId): Replaces the session-scoped {@code Cart} object — the Order Service
 *       retrieves cart contents from Redis/CartStateService using this ID</li>
 * </ul>
 *
 * <h3>Validation</h3>
 * <p>Uses Jakarta Bean Validation ({@code jakarta.validation.constraints.NotBlank})
 * on 21 required String fields. The two address line 2 fields ({@code shipAddress2},
 * {@code billAddress2}) are nullable — matching the monolith's schema where
 * {@code Account.address2} has no required constraint.</p>
 *
 * <h3>Card Types</h3>
 * <p>The {@code cardType} field accepts values matching the monolith's
 * {@code OrderActionBean.CARD_TYPE_LIST}: "Visa", "MasterCard", "American Express".</p>
 *
 * @author Blitzy Platform
 * @see com.jpetstore.order.controller.OrderController
 * @see com.jpetstore.order.service.OrderService
 */
public class OrderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    // =========================================================================
    // Field 1: User identity
    // Corresponds to Order.initOrder() line 288: username = account.getUsername()
    // Typically extracted from the JWT token in the Authorization header and
    // included in the request body for explicit identification.
    // =========================================================================

    /** The username of the authenticated user placing the order. */
    @NotBlank
    private String username;

    // =========================================================================
    // Fields 2–9: Shipping address
    // Corresponds to Order.initOrder() lines 291–298.
    // Initially populated from the account's address, but the user may modify
    // these on the ShippingForm.jsp if shippingAddressRequired is true.
    // =========================================================================

    /** Shipping recipient first name (from Account.firstName, line 291). */
    @NotBlank
    private String shipToFirstName;

    /** Shipping recipient last name (from Account.lastName, line 292). */
    @NotBlank
    private String shipToLastName;

    /** Shipping address line 1 (from Account.address1, line 293). */
    @NotBlank
    private String shipAddress1;

    /**
     * Shipping address line 2 (from Account.address2, line 294).
     * Nullable — not all addresses have a second line.
     */
    private String shipAddress2;

    /** Shipping city (from Account.city, line 295). */
    @NotBlank
    private String shipCity;

    /** Shipping state/province (from Account.state, line 296). */
    @NotBlank
    private String shipState;

    /** Shipping ZIP/postal code (from Account.zip, line 297). */
    @NotBlank
    private String shipZip;

    /** Shipping country (from Account.country, line 298). */
    @NotBlank
    private String shipCountry;

    // =========================================================================
    // Fields 10–17: Billing address
    // Corresponds to Order.initOrder() lines 300–307.
    // Initially populated from the account's address. In the monolith, the
    // billing address defaults to the same as the account address and may
    // differ from shipping if the user modifies the shipping address separately.
    // =========================================================================

    /** Billing recipient first name (from Account.firstName, line 300). */
    @NotBlank
    private String billToFirstName;

    /** Billing recipient last name (from Account.lastName, line 301). */
    @NotBlank
    private String billToLastName;

    /** Billing address line 1 (from Account.address1, line 302). */
    @NotBlank
    private String billAddress1;

    /**
     * Billing address line 2 (from Account.address2, line 303).
     * Nullable — not all addresses have a second line.
     */
    private String billAddress2;

    /** Billing city (from Account.city, line 304). */
    @NotBlank
    private String billCity;

    /** Billing state/province (from Account.state, line 305). */
    @NotBlank
    private String billState;

    /** Billing ZIP/postal code (from Account.zip, line 306). */
    @NotBlank
    private String billZip;

    /** Billing country (from Account.country, line 307). */
    @NotBlank
    private String billCountry;

    // =========================================================================
    // Fields 18–20: Payment information
    // Corresponds to Order.initOrder() lines 311–313.
    // The monolith sets defaults: creditCard="999 9999 9999 9999",
    // expiryDate="12/03", cardType="Visa". The user can modify these on the
    // NewOrderForm.jsp before submitting. Card type values are restricted to
    // the CARD_TYPE_LIST in OrderActionBean: "Visa", "MasterCard",
    // "American Express".
    // =========================================================================

    /** Credit card number (default: "999 9999 9999 9999", line 311). */
    @NotBlank
    private String creditCard;

    /** Credit card expiry date in MM/YY format (default: "12/03", line 312). */
    @NotBlank
    private String expiryDate;

    /**
     * Credit card type (default: "Visa", line 313).
     * Valid values: "Visa", "MasterCard", "American Express"
     * (per OrderActionBean.CARD_TYPE_LIST).
     */
    @NotBlank
    private String cardType;

    // =========================================================================
    // Fields 21–22: Courier and locale
    // Corresponds to Order.initOrder() lines 314–315.
    // =========================================================================

    /** Shipping courier (default: "UPS", line 314). */
    @NotBlank
    private String courier;

    /** Order locale code (default: "CA", line 315). */
    @NotBlank
    private String locale;

    // =========================================================================
    // Field 23: Cart session reference
    // Replaces the in-process Cart parameter from monolith's
    // initOrder(Account, Cart). Instead of passing the Cart object directly,
    // the client passes the cart session ID. The Order Service retrieves cart
    // contents from Redis via CartStateService and uses those items to create
    // line items (replicating Order.java lines 318–322).
    // =========================================================================

    /**
     * Reference to the externalized cart stored in Redis.
     * The Order Service retrieves cart contents (item IDs, quantities) from
     * {@code CartStateService} using this ID to construct order line items.
     */
    @NotBlank
    private String cartSessionId;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * No-arg constructor required for JSON deserialization by Jackson.
     * All fields are populated via setter methods during deserialization
     * of the incoming {@code POST /api/orders} request body.
     */
    public OrderRequest() {
        // No-arg constructor for Jackson JSON deserialization
    }

    // =========================================================================
    // Getters and Setters — User Identity
    // =========================================================================

    /**
     * Returns the username of the authenticated user placing the order.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the username of the authenticated user placing the order.
     *
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    // =========================================================================
    // Getters and Setters — Shipping Address
    // =========================================================================

    /**
     * Returns the shipping recipient's first name.
     *
     * @return the shipping first name
     */
    public String getShipToFirstName() {
        return shipToFirstName;
    }

    /**
     * Sets the shipping recipient's first name.
     *
     * @param shipToFirstName the shipping first name to set
     */
    public void setShipToFirstName(String shipToFirstName) {
        this.shipToFirstName = shipToFirstName;
    }

    /**
     * Returns the shipping recipient's last name.
     *
     * @return the shipping last name
     */
    public String getShipToLastName() {
        return shipToLastName;
    }

    /**
     * Sets the shipping recipient's last name.
     *
     * @param shipToLastName the shipping last name to set
     */
    public void setShipToLastName(String shipToLastName) {
        this.shipToLastName = shipToLastName;
    }

    /**
     * Returns the first line of the shipping address.
     *
     * @return shipping address line 1
     */
    public String getShipAddress1() {
        return shipAddress1;
    }

    /**
     * Sets the first line of the shipping address.
     *
     * @param shipAddress1 the shipping address line 1 to set
     */
    public void setShipAddress1(String shipAddress1) {
        this.shipAddress1 = shipAddress1;
    }

    /**
     * Returns the second line of the shipping address (nullable).
     *
     * @return shipping address line 2, or {@code null}
     */
    public String getShipAddress2() {
        return shipAddress2;
    }

    /**
     * Sets the second line of the shipping address (nullable).
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
     * Returns the shipping ZIP/postal code.
     *
     * @return the shipping ZIP code
     */
    public String getShipZip() {
        return shipZip;
    }

    /**
     * Sets the shipping ZIP/postal code.
     *
     * @param shipZip the shipping ZIP code to set
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

    // =========================================================================
    // Getters and Setters — Billing Address
    // =========================================================================

    /**
     * Returns the billing recipient's first name.
     *
     * @return the billing first name
     */
    public String getBillToFirstName() {
        return billToFirstName;
    }

    /**
     * Sets the billing recipient's first name.
     *
     * @param billToFirstName the billing first name to set
     */
    public void setBillToFirstName(String billToFirstName) {
        this.billToFirstName = billToFirstName;
    }

    /**
     * Returns the billing recipient's last name.
     *
     * @return the billing last name
     */
    public String getBillToLastName() {
        return billToLastName;
    }

    /**
     * Sets the billing recipient's last name.
     *
     * @param billToLastName the billing last name to set
     */
    public void setBillToLastName(String billToLastName) {
        this.billToLastName = billToLastName;
    }

    /**
     * Returns the first line of the billing address.
     *
     * @return billing address line 1
     */
    public String getBillAddress1() {
        return billAddress1;
    }

    /**
     * Sets the first line of the billing address.
     *
     * @param billAddress1 the billing address line 1 to set
     */
    public void setBillAddress1(String billAddress1) {
        this.billAddress1 = billAddress1;
    }

    /**
     * Returns the second line of the billing address (nullable).
     *
     * @return billing address line 2, or {@code null}
     */
    public String getBillAddress2() {
        return billAddress2;
    }

    /**
     * Sets the second line of the billing address (nullable).
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
     * Returns the billing ZIP/postal code.
     *
     * @return the billing ZIP code
     */
    public String getBillZip() {
        return billZip;
    }

    /**
     * Sets the billing ZIP/postal code.
     *
     * @param billZip the billing ZIP code to set
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

    // =========================================================================
    // Getters and Setters — Payment Information
    // =========================================================================

    /**
     * Returns the credit card number.
     *
     * @return the credit card number
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
     * Returns the credit card expiry date (MM/YY format).
     *
     * @return the expiry date
     */
    public String getExpiryDate() {
        return expiryDate;
    }

    /**
     * Sets the credit card expiry date (MM/YY format).
     *
     * @param expiryDate the expiry date to set
     */
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    /**
     * Returns the credit card type.
     * Expected values: "Visa", "MasterCard", "American Express".
     *
     * @return the card type
     */
    public String getCardType() {
        return cardType;
    }

    /**
     * Sets the credit card type.
     * Expected values: "Visa", "MasterCard", "American Express".
     *
     * @param cardType the card type to set
     */
    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    // =========================================================================
    // Getters and Setters — Courier and Locale
    // =========================================================================

    /**
     * Returns the shipping courier name.
     *
     * @return the courier
     */
    public String getCourier() {
        return courier;
    }

    /**
     * Sets the shipping courier name.
     *
     * @param courier the courier to set
     */
    public void setCourier(String courier) {
        this.courier = courier;
    }

    /**
     * Returns the order locale code.
     *
     * @return the locale code
     */
    public String getLocale() {
        return locale;
    }

    /**
     * Sets the order locale code.
     *
     * @param locale the locale code to set
     */
    public void setLocale(String locale) {
        this.locale = locale;
    }

    // =========================================================================
    // Getters and Setters — Cart Session Reference
    // =========================================================================

    /**
     * Returns the externalized cart session ID.
     * The Order Service uses this ID to retrieve cart contents (item IDs and
     * quantities) from Redis via {@code CartStateService}.
     *
     * @return the cart session ID
     */
    public String getCartSessionId() {
        return cartSessionId;
    }

    /**
     * Sets the externalized cart session ID.
     *
     * @param cartSessionId the cart session ID to set
     */
    public void setCartSessionId(String cartSessionId) {
        this.cartSessionId = cartSessionId;
    }

}
