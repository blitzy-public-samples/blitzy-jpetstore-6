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
 * JPA entity mapping to the {@code bannerdata} PostgreSQL table.
 *
 * <p>This table stores banner display information keyed by favourite category.
 * In the original monolith, the {@code bannerdata} table is joined with the
 * {@code profile} table via {@code PROFILE.FAVCATEGORY = BANNERDATA.FAVCATEGORY}
 * to resolve the banner image name for personalized user pages.</p>
 *
 * <p>The entity is read-only in normal application flow — banner data is
 * pre-seeded and looked up by the favourite category identifier.</p>
 *
 * <p>HSQLDB source schema (lines 61-65 of jpetstore-hsqldb-schema.sql):</p>
 * <pre>{@code
 * CREATE TABLE bannerdata (
 *     favcategory VARCHAR(80)  NOT NULL,
 *     bannername  VARCHAR(255) NULL,
 *     CONSTRAINT pk_bannerdata PRIMARY KEY (favcategory)
 * );
 * }</pre>
 *
 * @author Blitzy Platform
 * @see com.jpetstore.account.entity.Profile
 */
@Entity
@Table(name = "bannerdata")
public class BannerData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The favourite category identifier serving as the primary key.
     * Maps to the {@code favcategory} column (VARCHAR 80, NOT NULL).
     * This value corresponds to a category ID such as "FISH", "DOGS", etc.
     */
    @Id
    @Column(name = "favcategory", length = 80, nullable = false)
    private String favcategory;

    /**
     * The banner image name associated with the favourite category.
     * Maps to the {@code bannername} column (VARCHAR 255, nullable).
     * Contains the path or identifier of the banner image to display,
     * for example {@code "<image src=\"../images/banner_fish.gif\">"}.
     */
    @Column(name = "bannername", length = 255)
    private String bannername;

    /**
     * Default no-arg constructor required by the JPA specification.
     * Hibernate and other JPA providers use this constructor for
     * entity instantiation during result set mapping.
     */
    public BannerData() {
        // Required by JPA specification
    }

    /**
     * Parameterized constructor for convenient entity creation.
     *
     * @param favcategory the favourite category identifier (primary key), must not be null
     * @param bannername  the banner image name, may be null
     */
    public BannerData(String favcategory, String bannername) {
        this.favcategory = favcategory;
        this.bannername = bannername;
    }

    /**
     * Returns the favourite category identifier (primary key).
     *
     * @return the favourite category identifier, never null for persisted entities
     */
    public String getFavcategory() {
        return favcategory;
    }

    /**
     * Sets the favourite category identifier (primary key).
     *
     * @param favcategory the favourite category identifier to set
     */
    public void setFavcategory(String favcategory) {
        this.favcategory = favcategory;
    }

    /**
     * Returns the banner image name associated with the favourite category.
     *
     * @return the banner image name, may be null
     */
    public String getBannername() {
        return bannername;
    }

    /**
     * Sets the banner image name associated with the favourite category.
     *
     * @param bannername the banner image name to set, may be null
     */
    public void setBannername(String bannername) {
        this.bannername = bannername;
    }

    /**
     * Determines equality based on the {@code favcategory} primary key field.
     * Two {@code BannerData} instances are considered equal if they have the
     * same non-null favourite category identifier.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects are equal based on primary key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BannerData that = (BannerData) o;
        return Objects.equals(favcategory, that.favcategory);
    }

    /**
     * Computes the hash code based on the {@code favcategory} primary key field.
     * Consistent with {@link #equals(Object)} — two equal objects produce the same hash code.
     *
     * @return the hash code based on the favourite category identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(favcategory);
    }

    /**
     * Returns a string representation of this {@code BannerData} entity
     * suitable for debugging and logging purposes.
     *
     * @return a string containing the favcategory and bannername field values
     */
    @Override
    public String toString() {
        return "BannerData{"
                + "favcategory='" + favcategory + '\''
                + ", bannername='" + bannername + '\''
                + '}';
    }
}
