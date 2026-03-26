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
package com.jpetstore.account.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a row in the {@code account} PostgreSQL table.
 * <p>
 * This entity maps exclusively to the {@code account} table and contains only
 * core user-identity and contact information (userid, email, name, address, phone).
 * Related data is stored in separate entities:
 * <ul>
 *   <li>{@code Signon} — authentication credentials (password)</li>
 *   <li>{@code Profile} — user preferences (language, favourite category, list/banner options)</li>
 *   <li>{@code BannerData} — banner display data keyed by favourite category</li>
 * </ul>
 * <p>
 * Field naming convention: Java field names follow readable camelCase (e.g. {@code address1}),
 * while {@code @Column} annotations map to the actual PostgreSQL column names (e.g. {@code addr1}).
 * The primary key field {@code userid} matches the column name directly.
 *
 * @see com.jpetstore.account.entity.Profile
 * @see com.jpetstore.account.entity.Signon
 * @see com.jpetstore.account.entity.BannerData
 */
@Entity
@Table(name = "account")
public class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key — maps to {@code account.userid VARCHAR(80) NOT NULL}.
     * This is the unique user identifier across the Account bounded context.
     */
    @Id
    @Column(name = "userid", length = 80, nullable = false)
    private String userid;

    /**
     * User email address — maps to {@code account.email VARCHAR(80) NOT NULL}.
     */
    @Column(name = "email", length = 80, nullable = false)
    private String email;

    /**
     * User first name — maps to {@code account.firstname VARCHAR(80) NOT NULL}.
     */
    @Column(name = "firstname", length = 80, nullable = false)
    private String firstname;

    /**
     * User last name — maps to {@code account.lastname VARCHAR(80) NOT NULL}.
     */
    @Column(name = "lastname", length = 80, nullable = false)
    private String lastname;

    /**
     * Account status code — maps to {@code account.status VARCHAR(2) NULL}.
     * Nullable per the database schema.
     */
    @Column(name = "status", length = 2)
    private String status;

    /**
     * Primary address line — maps to {@code account.addr1 VARCHAR(80) NOT NULL}.
     * Java field uses readable name {@code address1}; column mapping handles
     * the translation to the shorter {@code addr1} column name.
     */
    @Column(name = "addr1", length = 80, nullable = false)
    private String address1;

    /**
     * Secondary address line — maps to {@code account.addr2 VARCHAR(40) NULL}.
     * Nullable per the database schema.
     */
    @Column(name = "addr2", length = 40)
    private String address2;

    /**
     * City — maps to {@code account.city VARCHAR(80) NOT NULL}.
     */
    @Column(name = "city", length = 80, nullable = false)
    private String city;

    /**
     * State or province — maps to {@code account.state VARCHAR(80) NOT NULL}.
     */
    @Column(name = "state", length = 80, nullable = false)
    private String state;

    /**
     * Postal/ZIP code — maps to {@code account.zip VARCHAR(20) NOT NULL}.
     */
    @Column(name = "zip", length = 20, nullable = false)
    private String zip;

    /**
     * Country — maps to {@code account.country VARCHAR(20) NOT NULL}.
     */
    @Column(name = "country", length = 20, nullable = false)
    private String country;

    /**
     * Phone number — maps to {@code account.phone VARCHAR(80) NOT NULL}.
     */
    @Column(name = "phone", length = 80, nullable = false)
    private String phone;

    /**
     * Default no-argument constructor required by the JPA specification.
     * Hibernate and other JPA providers use this constructor for entity instantiation.
     */
    public Account() {
        // JPA-required no-arg constructor
    }

    /**
     * All-arguments constructor for convenient programmatic construction.
     *
     * @param userid   unique user identifier (primary key)
     * @param email    user email address
     * @param firstname user first name
     * @param lastname  user last name
     * @param status   account status code (nullable)
     * @param address1 primary address line
     * @param address2 secondary address line (nullable)
     * @param city     city
     * @param state    state or province
     * @param zip      postal/ZIP code
     * @param country  country
     * @param phone    phone number
     */
    public Account(String userid, String email, String firstname, String lastname,
                   String status, String address1, String address2, String city,
                   String state, String zip, String country, String phone) {
        this.userid = userid;
        this.email = email;
        this.firstname = firstname;
        this.lastname = lastname;
        this.status = status;
        this.address1 = address1;
        this.address2 = address2;
        this.city = city;
        this.state = state;
        this.zip = zip;
        this.country = country;
        this.phone = phone;
    }

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    public String getUserid() {
        return userid;
    }

    public void setUserid(String userid) {
        this.userid = userid;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstname() {
        return firstname;
    }

    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public void setLastname(String lastname) {
        this.lastname = lastname;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAddress1() {
        return address1;
    }

    public void setAddress1(String address1) {
        this.address1 = address1;
    }

    public String getAddress2() {
        return address2;
    }

    public void setAddress2(String address2) {
        this.address2 = address2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZip() {
        return zip;
    }

    public void setZip(String zip) {
        this.zip = zip;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    // ========================================================================
    // equals, hashCode, toString — based on primary key (userid)
    // ========================================================================

    /**
     * Two {@code Account} instances are considered equal if and only if they have
     * the same non-null {@code userid}. This follows the JPA best-practice of using
     * the natural/business key (here the PK) for equality, ensuring correct behavior
     * in collections and JPA managed contexts.
     *
     * @param o the object to compare
     * @return {@code true} if the objects represent the same account
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Account account = (Account) o;
        return Objects.equals(userid, account.userid);
    }

    /**
     * Hash code derived from the {@code userid} primary key to maintain the
     * contract with {@link #equals(Object)}.
     *
     * @return hash code based on userid
     */
    @Override
    public int hashCode() {
        return Objects.hash(userid);
    }

    /**
     * Returns a string representation including all fields for debugging purposes.
     *
     * @return human-readable string with all account fields
     */
    @Override
    public String toString() {
        return "Account{"
                + "userid='" + userid + '\''
                + ", email='" + email + '\''
                + ", firstname='" + firstname + '\''
                + ", lastname='" + lastname + '\''
                + ", status='" + status + '\''
                + ", address1='" + address1 + '\''
                + ", address2='" + address2 + '\''
                + ", city='" + city + '\''
                + ", state='" + state + '\''
                + ", zip='" + zip + '\''
                + ", country='" + country + '\''
                + ", phone='" + phone + '\''
                + '}';
    }
}
