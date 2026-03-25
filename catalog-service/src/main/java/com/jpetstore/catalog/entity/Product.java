/*
 * JPetStore Catalog Service — Product JPA Entity
 *
 * Converted from the monolith's org.mybatis.jpetstore.domain.Product JavaBean
 * into a JPA @Entity mapped to the PostgreSQL "product" table in the
 * Catalog/Inventory bounded context.
 *
 * Column mapping (HSQLDB → PostgreSQL JPA):
 *   productid  VARCHAR(10)  NOT NULL PK  → @Id productId
 *   category   VARCHAR(10)  NOT NULL FK  → @ManyToOne Category (references category.catid)
 *   name       VARCHAR(80)  NULL         → name
 *   descn      VARCHAR(255) NULL         → description (Java field) mapped to DB column "descn"
 *
 * Indexes (from HSQLDB schema):
 *   productCat  on column "category"
 *   productName on column "name"
 *
 * Intra-service FK:
 *   product.category → category.catid (preserved as @ManyToOne relationship)
 *
 * Business logic preserved from monolith:
 *   - setProductId() trims whitespace (mirrors original behavior at line 39 of source)
 *   - toString() returns getName() (mirrors original behavior at line 68 of source)
 *
 * Design decisions:
 *   - No @Version annotation — optimistic locking is only used on Inventory entity
 *   - No cross-service foreign key relationships from this entity
 *   - The monolith's String categoryId field is replaced by a full Category entity reference;
 *     the category ID can be accessed via category.getCatId()
 *   - Jakarta namespace (jakarta.persistence.*) per Spring Boot 3.5.x / Jakarta EE 10
 *   - FetchType.LAZY on the Category relationship for performance
 */
package com.jpetstore.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.io.Serializable;

/**
 * JPA entity representing a product in the JPetStore catalog.
 *
 * <p>Maps to the PostgreSQL {@code product} table. Each product belongs to exactly
 * one {@link Category} and groups related {@code Item} entities (e.g., product
 * "Angelfish" under category "FISH" may have items "EST-1" and "EST-2").</p>
 *
 * <p>This is a structural conversion of the monolith's
 * {@code org.mybatis.jpetstore.domain.Product} with zero business logic changes.
 * The original MyBatis column aliases ({@code CATEGORY AS categoryId},
 * {@code DESCN AS description}) are replaced by JPA {@code @Column(name=...)}
 * annotations and a {@code @ManyToOne} relationship for the category FK.</p>
 *
 * <p>Two database indexes are declared to match the original HSQLDB schema:
 * {@code productCat} on the {@code category} FK column and {@code productName}
 * on the {@code name} column.</p>
 *
 * @see Category
 */
@Entity
@Table(
    name = "product",
    indexes = {
        @Index(name = "productCat", columnList = "category"),
        @Index(name = "productName", columnList = "name")
    }
)
public class Product implements Serializable {

    /**
     * Serialization version UID matching the monolith's
     * {@code org.mybatis.jpetstore.domain.Product} class for wire-compatibility
     * during the coexistence window.
     */
    private static final long serialVersionUID = -7492639752670189553L;

    /**
     * Primary key — product identifier (e.g., "FI-SW-01", "K9-BD-01").
     * Maps to the {@code productid} column in the {@code product} table.
     * VARCHAR(10), NOT NULL.
     */
    @Id
    @Column(name = "productid", length = 10, nullable = false)
    private String productId;

    /**
     * The category that this product belongs to.
     *
     * <p>Replaces the monolith's {@code String categoryId} field with a full
     * JPA entity relationship. The database FK column is named {@code category}
     * (not {@code categoryid}) and it references the {@code catid} PK column in
     * the {@code category} table.</p>
     *
     * <p>Lazy-loaded to avoid unnecessary joins when only product-level data
     * is needed.</p>
     *
     * <p>The original category ID can be obtained via {@code getCategory().getCatId()}.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category", referencedColumnName = "catid", nullable = false)
    private Category category;

    /**
     * Display name for the product (e.g., "Angelfish", "Bulldog").
     * Maps to the {@code name} column. VARCHAR(80), nullable.
     */
    @Column(name = "name", length = 80)
    private String name;

    /**
     * Long description of the product, rendered in the catalog detail view.
     *
     * <p>Maps to the {@code descn} column in the database. Note: the Java field
     * name is {@code description} but the DB column is {@code descn}, matching
     * the original HSQLDB schema. This mirrors the MyBatis alias
     * {@code DESCN AS description} from {@code ProductMapper.xml}.</p>
     *
     * <p>VARCHAR(255), nullable.</p>
     */
    @Column(name = "descn", length = 255)
    private String description;

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    /**
     * Returns the product identifier.
     *
     * @return the product ID string, or {@code null} if not set
     */
    public String getProductId() {
        return productId;
    }

    /**
     * Sets the product identifier after trimming leading and trailing whitespace.
     *
     * <p>This preserves the monolith's original {@code setProductId()} behavior
     * (line 39 of the source {@code Product.java}) where the value was always
     * trimmed on assignment.</p>
     *
     * @param productId the product ID to set; must not be {@code null}
     */
    public void setProductId(String productId) {
        this.productId = productId.trim();
    }

    /**
     * Returns the category that this product belongs to.
     *
     * <p>Replaces the monolith's {@code getCategoryId()} which returned a String.
     * To obtain the category ID string, use {@code getCategory().getCatId()}.</p>
     *
     * @return the associated {@link Category} entity, or {@code null} if not loaded
     */
    public Category getCategory() {
        return category;
    }

    /**
     * Sets the category that this product belongs to.
     *
     * <p>Replaces the monolith's {@code setCategoryId(String)} by accepting a
     * full {@link Category} entity reference. The JPA provider manages the FK
     * column ({@code category}) in the database.</p>
     *
     * @param category the {@link Category} entity to associate with this product
     */
    public void setCategory(Category category) {
        this.category = category;
    }

    /**
     * Returns the display name of the product.
     *
     * @return the product name, or {@code null} if not set
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the display name of the product.
     *
     * @param name the product name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the long description of the product.
     *
     * @return the product description, or {@code null} if not set
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the long description of the product.
     *
     * @param description the product description to set
     */
    public void setDescription(String description) {
        this.description = description;
    }

    // ========================================================================
    // Object overrides
    // ========================================================================

    /**
     * Returns a string representation of this product, which is the product name.
     *
     * <p>Preserves the monolith's original {@code toString()} behavior
     * (line 68 of the source {@code Product.java}): {@code return getName();}.</p>
     *
     * @return the product name string, or {@code null} if the name is not set
     */
    @Override
    public String toString() {
        return getName();
    }

}
