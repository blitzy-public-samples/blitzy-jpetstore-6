/*
 * JPetStore Catalog Service — Item JPA Entity
 *
 * Converted from the monolith's org.mybatis.jpetstore.domain.Item JavaBean
 * into a JPA @Entity mapped to the PostgreSQL "item" table in the
 * Catalog/Inventory bounded context.
 *
 * Column mapping (HSQLDB → PostgreSQL JPA):
 *   itemid     VARCHAR(10)    NOT NULL PK  → @Id itemId
 *   productid  VARCHAR(10)    NOT NULL FK  → @ManyToOne Product (references product.productid)
 *   listprice  DECIMAL(10,2)  NULL         → listPrice (BigDecimal)
 *   unitcost   DECIMAL(10,2)  NULL         → unitCost (BigDecimal)
 *   supplier   INT            NULL FK      → @ManyToOne Supplier (references supplier.suppid)
 *   status     VARCHAR(2)     NULL         → status
 *   attr1      VARCHAR(80)    NULL         → attribute1 (Java field name differs from DB column)
 *   attr2      VARCHAR(80)    NULL         → attribute2
 *   attr3      VARCHAR(80)    NULL         → attribute3
 *   attr4      VARCHAR(80)    NULL         → attribute4
 *   attr5      VARCHAR(80)    NULL         → attribute5
 *
 * Index (from HSQLDB schema):
 *   itemProd on column "productid"
 *
 * Intra-service FKs:
 *   item.productid → product.productid  (preserved as @ManyToOne relationship)
 *   item.supplier  → supplier.suppid    (preserved as @ManyToOne relationship)
 *
 * CRITICAL: The monolith's Item.java contained a "quantity" field populated via a
 * JOIN with the INVENTORY table in ItemMapper.xml. In the decomposed architecture,
 * inventory quantity lives in the separate Inventory entity with @Version for
 * optimistic locking. The quantity field is NOT included in this entity.
 *
 * Business logic preserved from monolith:
 *   - setItemId() trims whitespace (mirrors original behavior at line 49 of source)
 *   - toString() returns "(" + getItemId() + "-" + getProduct().getProductId() + ")"
 *     (mirrors original behavior at line 142 of source)
 *
 * Design decisions:
 *   - No @Version annotation — optimistic locking is only used on Inventory entity
 *   - No cross-service foreign key relationships from this entity
 *   - The monolith's String productId field is replaced by a full Product entity reference;
 *     the product ID can be accessed via product.getProductId()
 *   - The monolith's int supplierId field is replaced by a full Supplier entity reference
 *   - Jakarta namespace (jakarta.persistence.*) per Spring Boot 3.5.x / Jakarta EE 10
 *   - FetchType.LAZY on both ManyToOne relationships for performance
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
import java.math.BigDecimal;

/**
 * JPA entity representing an item in the JPetStore catalog.
 *
 * <p>Maps to the PostgreSQL {@code item} table. Each item belongs to exactly
 * one {@link Product} and is optionally associated with a {@link Supplier}.
 * Items are the lowest-granularity SKU in the catalog hierarchy
 * (Category → Product → Item).</p>
 *
 * <p>This is a structural conversion of the monolith's
 * {@code org.mybatis.jpetstore.domain.Item} with zero business logic changes.
 * The original MyBatis column aliases ({@code ATTR1 AS attribute1},
 * {@code SUPPLIER AS supplierId}) are replaced by JPA {@code @Column(name=...)}
 * annotations and {@code @ManyToOne} relationships for the FK columns.</p>
 *
 * <p>A database index ({@code itemProd}) is declared on the {@code productid}
 * column to match the original HSQLDB schema.</p>
 *
 * <p><strong>Note:</strong> The monolith's {@code quantity} field (populated via
 * a JOIN with the INVENTORY table) is NOT present on this entity. In the decomposed
 * architecture, inventory quantity is managed by the separate {@code Inventory}
 * entity which includes {@code @Version} for optimistic locking on concurrent
 * decrement operations.</p>
 *
 * @see Product
 * @see Supplier
 */
@Entity
@Table(
    name = "item",
    indexes = {
        @Index(name = "itemProd", columnList = "productid")
    }
)
public class Item implements Serializable {

    /**
     * Serialization version UID matching the monolith's
     * {@code org.mybatis.jpetstore.domain.Item} class for wire-compatibility
     * during the coexistence window.
     */
    private static final long serialVersionUID = -2159121673445254631L;

    // ──────────────────────────────────────────────────────────────────────────
    // Primary Key
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Item identifier (primary key), e.g., "EST-1", "EST-2".
     * Maps to the {@code itemid} column in the {@code item} table.
     * VARCHAR(10), NOT NULL.
     */
    @Id
    @Column(name = "itemid", length = 10, nullable = false)
    private String itemId;

    // ──────────────────────────────────────────────────────────────────────────
    // Relationships
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The product that this item belongs to.
     *
     * <p>Replaces the monolith's separate {@code String productId} field with a full
     * JPA entity relationship. The database FK column is named {@code productid}
     * and it references the {@code productid} PK column in the {@code product} table.</p>
     *
     * <p>Lazy-loaded to avoid unnecessary joins when only item-level data is needed.</p>
     *
     * <p>The original product ID can be obtained via {@code getProduct().getProductId()}.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "productid", nullable = false)
    private Product product;

    /**
     * The supplier for this item.
     *
     * <p>Replaces the monolith's {@code int supplierId} field with a full JPA entity
     * relationship. The database FK column is named {@code supplier} and it references
     * the {@code suppid} PK column in the {@code supplier} table.</p>
     *
     * <p>Lazy-loaded to avoid unnecessary joins when only item-level data is needed.</p>
     *
     * <p>The original supplier ID can be obtained via {@code getSupplier().getSuppId()}.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier")
    private Supplier supplier;

    // ──────────────────────────────────────────────────────────────────────────
    // Pricing Fields
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * List price displayed to customers.
     * Maps to the {@code listprice} column. DECIMAL(10,2), nullable.
     */
    @Column(name = "listprice", precision = 10, scale = 2)
    private BigDecimal listPrice;

    /**
     * Unit cost to the store.
     * Maps to the {@code unitcost} column. DECIMAL(10,2), nullable.
     */
    @Column(name = "unitcost", precision = 10, scale = 2)
    private BigDecimal unitCost;

    // ──────────────────────────────────────────────────────────────────────────
    // Status and Attribute Fields
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Item status code (e.g., "P" for available).
     * Maps to the {@code status} column. VARCHAR(2), nullable.
     */
    @Column(name = "status", length = 2)
    private String status;

    /**
     * Attribute 1 of the item (e.g., breed or variety descriptor).
     *
     * <p>Maps to the {@code attr1} column in the database. Note: the Java field
     * name is {@code attribute1} but the DB column is {@code attr1}, matching
     * the original MyBatis alias {@code ATTR1 AS attribute1} from
     * {@code ItemMapper.xml}.</p>
     *
     * <p>VARCHAR(80), nullable.</p>
     */
    @Column(name = "attr1", length = 80)
    private String attribute1;

    /**
     * Attribute 2 of the item.
     * Maps to the {@code attr2} column. VARCHAR(80), nullable.
     */
    @Column(name = "attr2", length = 80)
    private String attribute2;

    /**
     * Attribute 3 of the item.
     * Maps to the {@code attr3} column. VARCHAR(80), nullable.
     */
    @Column(name = "attr3", length = 80)
    private String attribute3;

    /**
     * Attribute 4 of the item.
     * Maps to the {@code attr4} column. VARCHAR(80), nullable.
     */
    @Column(name = "attr4", length = 80)
    private String attribute4;

    /**
     * Attribute 5 of the item.
     * Maps to the {@code attr5} column. VARCHAR(80), nullable.
     */
    @Column(name = "attr5", length = 80)
    private String attribute5;

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    /**
     * Returns the item identifier.
     *
     * @return the item ID string, or {@code null} if not set
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets the item identifier after trimming leading and trailing whitespace.
     *
     * <p>This preserves the monolith's original {@code setItemId()} behavior
     * (line 49 of the source {@code Item.java}) where the value was always
     * trimmed on assignment.</p>
     *
     * @param itemId the item ID to set; must not be {@code null}
     */
    public void setItemId(String itemId) {
        this.itemId = itemId.trim();
    }

    /**
     * Returns the product that this item belongs to.
     *
     * <p>Replaces the monolith's separate {@code getProductId()} method.
     * To obtain the product ID string, use {@code getProduct().getProductId()}.</p>
     *
     * @return the associated {@link Product} entity, or {@code null} if not loaded
     */
    public Product getProduct() {
        return product;
    }

    /**
     * Sets the product that this item belongs to.
     *
     * <p>The JPA provider manages the FK column ({@code productid}) in the
     * database.</p>
     *
     * @param product the {@link Product} entity to associate with this item
     */
    public void setProduct(Product product) {
        this.product = product;
    }

    /**
     * Returns the supplier for this item.
     *
     * <p>Replaces the monolith's {@code getSupplierId()} which returned a
     * primitive {@code int}. To obtain the supplier ID, use
     * {@code getSupplier().getSuppId()}.</p>
     *
     * @return the associated {@link Supplier} entity, or {@code null} if not set
     */
    public Supplier getSupplier() {
        return supplier;
    }

    /**
     * Sets the supplier for this item.
     *
     * <p>Replaces the monolith's {@code setSupplierId(int)} by accepting a full
     * {@link Supplier} entity reference. The JPA provider manages the FK column
     * ({@code supplier}) in the database.</p>
     *
     * @param supplier the {@link Supplier} entity to associate with this item
     */
    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    /**
     * Returns the list price displayed to customers.
     *
     * @return the list price as a {@link BigDecimal}, or {@code null} if not set
     */
    public BigDecimal getListPrice() {
        return listPrice;
    }

    /**
     * Sets the list price displayed to customers.
     *
     * @param listPrice the list price to set
     */
    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    /**
     * Returns the unit cost to the store.
     *
     * @return the unit cost as a {@link BigDecimal}, or {@code null} if not set
     */
    public BigDecimal getUnitCost() {
        return unitCost;
    }

    /**
     * Sets the unit cost to the store.
     *
     * @param unitCost the unit cost to set
     */
    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    /**
     * Returns the item status code.
     *
     * @return the status code (max 2 characters), or {@code null} if not set
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the item status code.
     *
     * @param status the status code to set (max 2 characters)
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns attribute 1 of the item (e.g., breed or variety descriptor).
     *
     * @return attribute 1, or {@code null} if not set
     */
    public String getAttribute1() {
        return attribute1;
    }

    /**
     * Sets attribute 1 of the item.
     *
     * @param attribute1 attribute 1 to set (max 80 characters)
     */
    public void setAttribute1(String attribute1) {
        this.attribute1 = attribute1;
    }

    /**
     * Returns attribute 2 of the item.
     *
     * @return attribute 2, or {@code null} if not set
     */
    public String getAttribute2() {
        return attribute2;
    }

    /**
     * Sets attribute 2 of the item.
     *
     * @param attribute2 attribute 2 to set (max 80 characters)
     */
    public void setAttribute2(String attribute2) {
        this.attribute2 = attribute2;
    }

    /**
     * Returns attribute 3 of the item.
     *
     * @return attribute 3, or {@code null} if not set
     */
    public String getAttribute3() {
        return attribute3;
    }

    /**
     * Sets attribute 3 of the item.
     *
     * @param attribute3 attribute 3 to set (max 80 characters)
     */
    public void setAttribute3(String attribute3) {
        this.attribute3 = attribute3;
    }

    /**
     * Returns attribute 4 of the item.
     *
     * @return attribute 4, or {@code null} if not set
     */
    public String getAttribute4() {
        return attribute4;
    }

    /**
     * Sets attribute 4 of the item.
     *
     * @param attribute4 attribute 4 to set (max 80 characters)
     */
    public void setAttribute4(String attribute4) {
        this.attribute4 = attribute4;
    }

    /**
     * Returns attribute 5 of the item.
     *
     * @return attribute 5, or {@code null} if not set
     */
    public String getAttribute5() {
        return attribute5;
    }

    /**
     * Sets attribute 5 of the item.
     *
     * @param attribute5 attribute 5 to set (max 80 characters)
     */
    public void setAttribute5(String attribute5) {
        this.attribute5 = attribute5;
    }

    // ========================================================================
    // Object overrides
    // ========================================================================

    /**
     * Returns a string representation of this item in the format
     * {@code "(itemId-productId)"}.
     *
     * <p>Preserves the monolith's original {@code toString()} behavior
     * (line 142 of the source {@code Item.java}):
     * {@code return "(" + getItemId() + "-" + getProduct().getProductId() + ")";}</p>
     *
     * @return a string in the format "(itemId-productId)", e.g., "(EST-1-FI-SW-01)"
     */
    @Override
    public String toString() {
        return "(" + getItemId() + "-" + getProduct().getProductId() + ")";
    }

}
