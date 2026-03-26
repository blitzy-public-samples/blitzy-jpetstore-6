/*
 * JPetStore Catalog Service — Category JPA Entity
 *
 * Converted from the monolith's org.mybatis.jpetstore.domain.Category JavaBean
 * into a JPA @Entity mapped to the PostgreSQL "category" table in the
 * Catalog/Inventory bounded context.
 *
 * Column mapping (HSQLDB → PostgreSQL JPA):
 *   catid   VARCHAR(10) NOT NULL  → @Id catId
 *   name    VARCHAR(80) NULL      → name
 *   descn   VARCHAR(255) NULL     → description (Java field) mapped to DB column "descn"
 *
 * Business logic preserved from monolith:
 *   - setCatId() trims whitespace (mirrors original setCategoryId() behavior)
 *   - toString() returns the category ID (mirrors original getCategoryId() behavior)
 *
 * Design decisions:
 *   - No @Version annotation — optimistic locking is only used on Inventory entity
 *   - No cross-service foreign key relationships from this entity
 *   - Product entity references this entity via @ManyToOne (defined in Product.java)
 *   - Jakarta namespace (jakarta.persistence.*) per Spring Boot 3.5.x / Jakarta EE 10
 */
package com.jpetstore.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;

/**
 * JPA entity representing a category in the JPetStore catalog.
 *
 * <p>Maps to the PostgreSQL {@code category} table. Each category groups related
 * products (e.g., "FISH", "DOGS", "CATS"). The {@link com.jpetstore.catalog.entity.Product}
 * entity holds a {@code @ManyToOne} reference back to this entity.</p>
 *
 * <p>This is a structural conversion of the monolith's
 * {@code org.mybatis.jpetstore.domain.Category} with zero business logic changes.
 * The original MyBatis column aliases ({@code CATID AS categoryId},
 * {@code DESCN AS description}) are replaced by JPA {@code @Column(name=...)}
 * annotations.</p>
 *
 * @see com.jpetstore.catalog.entity.Product
 */
@Entity
@Table(name = "category")
public class Category implements Serializable {

    private static final long serialVersionUID = 3992469837058393712L;

    /**
     * Primary key — category identifier (e.g., "FISH", "DOGS", "CATS").
     * Maps to the {@code catid} column in the {@code category} table.
     * VARCHAR(10), NOT NULL.
     */
    @Id
    @Column(name = "catid", length = 10, nullable = false)
    private String catId;

    /**
     * Display name for the category (e.g., "Fish", "Dogs", "Cats").
     * Maps to the {@code name} column. VARCHAR(80), nullable.
     */
    @Column(name = "name", length = 80)
    private String name;

    /**
     * Long description of the category, rendered in the catalog UI.
     * Maps to the {@code descn} column in the database (note: Java field is
     * {@code description} but the DB column is {@code descn}, matching the
     * original HSQLDB schema).
     * VARCHAR(255), nullable.
     */
    @Column(name = "descn", length = 255)
    private String description;

    /**
     * Returns the category identifier.
     *
     * @return the category ID string, or {@code null} if not set
     */
    public String getCatId() {
        return catId;
    }

    /**
     * Sets the category identifier after trimming leading and trailing whitespace.
     * This preserves the monolith's original {@code setCategoryId()} behavior
     * where the value was always trimmed on assignment.
     *
     * @param catId the category ID to set; must not be {@code null}
     */
    public void setCatId(String catId) {
        this.catId = catId.trim();
    }

    /**
     * Returns the display name of the category.
     *
     * @return the category name, or {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name of the category.
     *
     * @param name the category name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the long description of the category.
     *
     * @return the category description, or {@code null} if not set
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the long description of the category.
     *
     * @param description the category description to set
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns a string representation of this category, which is the category ID.
     * Preserves the monolith's original {@code toString()} behavior.
     *
     * @return the category ID string
     */
    @Override
    public String toString() {
        return getCatId();
    }

}
