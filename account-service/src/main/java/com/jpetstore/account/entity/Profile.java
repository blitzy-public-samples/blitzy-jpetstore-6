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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity mapping to the {@code profile} PostgreSQL table in the Account Service.
 *
 * <p>Stores user preference data: language preference, favourite category for
 * personalization, and display options for the my-list sidebar and promotional banners.
 *
 * <p>This entity is extracted from the monolith's {@code Account} domain class where
 * profile fields were embedded alongside account fields. In the microservice architecture,
 * the profile table is separated into its own entity while sharing the {@code userid}
 * primary key with the {@code account} table.
 *
 * <h3>Column Mapping (HSQLDB → PostgreSQL)</h3>
 * <table>
 *   <tr><th>Column</th><th>Type</th><th>Monolith Field</th></tr>
 *   <tr><td>userid</td><td>varchar(80) PK, NOT NULL</td><td>Account.username</td></tr>
 *   <tr><td>langpref</td><td>varchar(80) NOT NULL</td><td>Account.languagePreference</td></tr>
 *   <tr><td>favcategory</td><td>varchar(30)</td><td>Account.favouriteCategoryId</td></tr>
 *   <tr><td>mylistopt</td><td>int</td><td>Account.listOption (boolean)</td></tr>
 *   <tr><td>banneropt</td><td>int</td><td>Account.bannerOption (boolean)</td></tr>
 * </table>
 *
 * <p>The {@code mylistopt} and {@code banneropt} columns are stored as INTEGER (0/1)
 * in the database. JPA/Hibernate handles the {@code int↔boolean} conversion automatically
 * via the {@code BasicTypeRegistry}, matching the monolith's MyBatis {@code <bind>}
 * conversion pattern.
 *
 * @see com.jpetstore.account.entity.Account
 */
@Entity
@Table(name = "profile")
public class Profile implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * User identifier — primary key shared with the {@code account} table.
     * Maps to the {@code userid} column (varchar(80), NOT NULL).
     */
    @Id
    @Column(name = "userid", length = 80, nullable = false)
    private String userid;

    /**
     * Language preference for the user interface.
     * Maps to the {@code langpref} column (varchar(80), NOT NULL).
     * Corresponds to the monolith's {@code Account.languagePreference} field,
     * aliased as {@code PROFILE.LANGPREF AS languagePreference} in AccountMapper.xml.
     */
    @Column(name = "langpref", length = 80, nullable = false)
    private String langpref;

    /**
     * Favourite product category identifier for personalization.
     * Maps to the {@code favcategory} column (varchar(30), nullable).
     * Corresponds to the monolith's {@code Account.favouriteCategoryId} field,
     * aliased as {@code PROFILE.FAVCATEGORY AS favouriteCategoryId} in AccountMapper.xml.
     */
    @Column(name = "favcategory", length = 30)
    private String favcategory;

    /**
     * Whether to display the personalized product list (my-list) sidebar.
     * Maps to the {@code mylistopt} column (int, nullable).
     * Stored as INTEGER (0/1) in the database; JPA/Hibernate converts to/from boolean.
     * Corresponds to the monolith's {@code Account.listOption} field, converted via
     * MyBatis {@code <bind name="listOptionValue" value="_parameter.isListOption() ? 1 : 0" />}.
     */
    @Column(name = "mylistopt")
    private boolean mylistopt;

    /**
     * Whether to display the promotional banner based on favourite category.
     * Maps to the {@code banneropt} column (int, nullable).
     * Stored as INTEGER (0/1) in the database; JPA/Hibernate converts to/from boolean.
     * Corresponds to the monolith's {@code Account.bannerOption} field, converted via
     * MyBatis {@code <bind name="bannerOptionValue" value="_parameter.isBannerOption() ? 1 : 0" />}.
     */
    @Column(name = "banneropt")
    private boolean banneropt;

    /**
     * Default no-argument constructor required by the JPA specification.
     * JPA providers use this constructor for entity instantiation during persistence
     * operations and query result mapping.
     */
    public Profile() {
        // Required by JPA spec — no initialization needed
    }

    /**
     * Parameterized constructor for programmatic entity creation.
     *
     * @param userid       the user identifier (primary key, shared with account table)
     * @param langpref     the language preference (must not be null per schema constraint)
     * @param favcategory  the favourite category identifier (nullable)
     * @param mylistopt    whether to display the personalized product list sidebar
     * @param banneropt    whether to display the promotional banner
     */
    public Profile(String userid, String langpref, String favcategory,
                   boolean mylistopt, boolean banneropt) {
        this.userid = userid;
        this.langpref = langpref;
        this.favcategory = favcategory;
        this.mylistopt = mylistopt;
        this.banneropt = banneropt;
    }

    /**
     * Returns the user identifier (primary key).
     *
     * @return the userid string, never null for persisted entities
     */
    public String getUserid() {
        return userid;
    }

    /**
     * Sets the user identifier (primary key).
     *
     * @param userid the user identifier to set
     */
    public void setUserid(String userid) {
        this.userid = userid;
    }

    /**
     * Returns the language preference.
     *
     * @return the language preference string, never null for persisted entities
     */
    public String getLangpref() {
        return langpref;
    }

    /**
     * Sets the language preference.
     *
     * @param langpref the language preference to set (must not be null per schema)
     */
    public void setLangpref(String langpref) {
        this.langpref = langpref;
    }

    /**
     * Returns the favourite category identifier used for personalization.
     *
     * @return the favourite category identifier, or null if not set
     */
    public String getFavcategory() {
        return favcategory;
    }

    /**
     * Sets the favourite category identifier for personalization.
     *
     * @param favcategory the favourite category identifier to set (nullable)
     */
    public void setFavcategory(String favcategory) {
        this.favcategory = favcategory;
    }

    /**
     * Returns whether the personalized product list (my-list) sidebar is enabled.
     *
     * @return {@code true} if the my-list sidebar should be displayed, {@code false} otherwise
     */
    public boolean isMylistopt() {
        return mylistopt;
    }

    /**
     * Sets whether the personalized product list (my-list) sidebar is enabled.
     *
     * @param mylistopt {@code true} to display the my-list sidebar, {@code false} to hide it
     */
    public void setMylistopt(boolean mylistopt) {
        this.mylistopt = mylistopt;
    }

    /**
     * Returns whether the promotional banner is enabled.
     *
     * @return {@code true} if the banner should be displayed, {@code false} otherwise
     */
    public boolean isBanneropt() {
        return banneropt;
    }

    /**
     * Sets whether the promotional banner is enabled.
     *
     * @param banneropt {@code true} to display the banner, {@code false} to hide it
     */
    public void setBanneropt(boolean banneropt) {
        this.banneropt = banneropt;
    }

    /**
     * Compares this profile with another object for equality based on the primary key
     * ({@code userid}). Two Profile entities are considered equal if and only if they
     * have the same non-null userid value.
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
        Profile profile = (Profile) o;
        return Objects.equals(userid, profile.userid);
    }

    /**
     * Returns a hash code based on the primary key ({@code userid}).
     * Consistent with {@link #equals(Object)} — entities with the same userid
     * produce the same hash code.
     *
     * @return the hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(userid);
    }

    /**
     * Returns a string representation of this profile for debugging purposes.
     * Includes all fields for full diagnostic visibility.
     *
     * @return a string representation of this Profile entity
     */
    @Override
    public String toString() {
        return "Profile{"
                + "userid='" + userid + '\''
                + ", langpref='" + langpref + '\''
                + ", favcategory='" + favcategory + '\''
                + ", mylistopt=" + mylistopt
                + ", banneropt=" + banneropt
                + '}';
    }
}
