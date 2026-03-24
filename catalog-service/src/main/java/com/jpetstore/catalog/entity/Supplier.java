/*
 * Copyright 2010-2025 the original author or authors.
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
package com.jpetstore.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;

/**
 * JPA entity representing a supplier in the Catalog Service's PostgreSQL database.
 *
 * <p>This entity maps to the {@code supplier} table and has no direct counterpart in the
 * original monolith's Java domain layer. In the monolith, supplier information was only
 * referenced via a foreign key ({@code supplierId}) on the {@code Item} domain class.
 * In the decomposed microservices architecture, Supplier is promoted to a full JPA entity
 * with all columns mapped.</p>
 *
 * <p>The supplier table is part of the Catalog/Inventory bounded context. It is referenced
 * by the {@code Item} entity via a {@code @ManyToOne} relationship (item.supplier → supplier.suppid).
 * This entity itself has no outgoing relationships to other entities.</p>
 *
 * <h3>Column Mapping (HSQLDB → PostgreSQL)</h3>
 * <pre>
 * HSQLDB Column   | PostgreSQL Column | Java Field | Type
 * ─────────────────────────────────────────────────────────
 * SUPPID (PK)     | suppid            | suppId     | Integer
 * NAME            | name              | name       | String(80)
 * STATUS          | status            | status     | String(2), NOT NULL
 * ADDR1           | addr1             | addr1      | String(80)
 * ADDR2           | addr2             | addr2      | String(80)
 * CITY            | city              | city       | String(80)
 * STATE           | state             | state      | String(80)
 * ZIP             | zip               | zip        | String(5)
 * PHONE           | phone             | phone      | String(80)
 * </pre>
 *
 * @see com.jpetstore.catalog.entity.Item Item entity references Supplier via @ManyToOne
 */
@Entity
@Table(name = "supplier")
public class Supplier implements Serializable {

    private static final long serialVersionUID = 1L;

    // ──────────────────────────────────────────────────────────────────────────
    // Primary Key
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Supplier identifier (primary key).
     * Maps to the {@code suppid} column (INT NOT NULL) in the supplier table.
     * Uses boxed {@link Integer} type as required by JPA for {@code @Id} fields.
     */
    @Id
    @Column(name = "suppid", nullable = false)
    private Integer suppId;

    // ──────────────────────────────────────────────────────────────────────────
    // Business Fields
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Supplier name.
     * Maps to the {@code name} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "name", length = 80)
    private String name;

    /**
     * Supplier status code.
     * Maps to the {@code status} column (VARCHAR(2) NOT NULL) in the supplier table.
     * This is the only non-nullable, non-PK column on the supplier table.
     */
    @Column(name = "status", length = 2, nullable = false)
    private String status;

    /**
     * Supplier address line 1.
     * Maps to the {@code addr1} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "addr1", length = 80)
    private String addr1;

    /**
     * Supplier address line 2.
     * Maps to the {@code addr2} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "addr2", length = 80)
    private String addr2;

    /**
     * Supplier city.
     * Maps to the {@code city} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "city", length = 80)
    private String city;

    /**
     * Supplier state or province.
     * Maps to the {@code state} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "state", length = 80)
    private String state;

    /**
     * Supplier postal/ZIP code.
     * Maps to the {@code zip} column (VARCHAR(5) NULL) in the supplier table.
     * Note the short maximum length of 5 characters.
     */
    @Column(name = "zip", length = 5)
    private String zip;

    /**
     * Supplier phone number.
     * Maps to the {@code phone} column (VARCHAR(80) NULL) in the supplier table.
     */
    @Column(name = "phone", length = 80)
    private String phone;

    // ──────────────────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Default no-argument constructor required by JPA specification.
     */
    public Supplier() {
        // JPA requires a no-arg constructor
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters and Setters
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the supplier identifier (primary key).
     *
     * @return the supplier ID, or {@code null} if not yet assigned
     */
    public Integer getSuppId() {
        return suppId;
    }

    /**
     * Sets the supplier identifier (primary key).
     *
     * @param suppId the supplier ID to set
     */
    public void setSuppId(Integer suppId) {
        this.suppId = suppId;
    }

    /**
     * Returns the supplier name.
     *
     * @return the supplier name, or {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the supplier name.
     *
     * @param name the supplier name to set (max 80 characters)
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the supplier status code.
     *
     * @return the status code (max 2 characters), never {@code null} for persisted entities
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the supplier status code.
     *
     * @param status the status code to set (max 2 characters, must not be null)
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the supplier address line 1.
     *
     * @return address line 1, or {@code null} if not set
     */
    public String getAddr1() {
        return addr1;
    }

    /**
     * Sets the supplier address line 1.
     *
     * @param addr1 address line 1 to set (max 80 characters)
     */
    public void setAddr1(String addr1) {
        this.addr1 = addr1;
    }

    /**
     * Returns the supplier address line 2.
     *
     * @return address line 2, or {@code null} if not set
     */
    public String getAddr2() {
        return addr2;
    }

    /**
     * Sets the supplier address line 2.
     *
     * @param addr2 address line 2 to set (max 80 characters)
     */
    public void setAddr2(String addr2) {
        this.addr2 = addr2;
    }

    /**
     * Returns the supplier city.
     *
     * @return the city, or {@code null} if not set
     */
    public String getCity() {
        return city;
    }

    /**
     * Sets the supplier city.
     *
     * @param city the city to set (max 80 characters)
     */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * Returns the supplier state or province.
     *
     * @return the state, or {@code null} if not set
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the supplier state or province.
     *
     * @param state the state to set (max 80 characters)
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns the supplier postal/ZIP code.
     *
     * @return the ZIP code (max 5 characters), or {@code null} if not set
     */
    public String getZip() {
        return zip;
    }

    /**
     * Sets the supplier postal/ZIP code.
     *
     * @param zip the ZIP code to set (max 5 characters)
     */
    public void setZip(String zip) {
        this.zip = zip;
    }

    /**
     * Returns the supplier phone number.
     *
     * @return the phone number, or {@code null} if not set
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Sets the supplier phone number.
     *
     * @param phone the phone number to set (max 80 characters)
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Object Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns a string representation of this supplier entity.
     * Includes the supplier ID and name for easy identification in logs and debugging.
     *
     * @return a descriptive string representation of this supplier
     */
    @Override
    public String toString() {
        return "Supplier{" +
                "suppId=" + suppId +
                ", name='" + name + '\'' +
                ", status='" + status + '\'' +
                ", addr1='" + addr1 + '\'' +
                ", addr2='" + addr2 + '\'' +
                ", city='" + city + '\'' +
                ", state='" + state + '\'' +
                ", zip='" + zip + '\'' +
                ", phone='" + phone + '\'' +
                '}';
    }
}
