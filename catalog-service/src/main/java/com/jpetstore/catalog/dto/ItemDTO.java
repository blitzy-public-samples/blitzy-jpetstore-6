/*
 * ItemDTO.java — REST Response DTO for Items (Catalog Service)
 *
 * Plain POJO that decouples the Item JPA entity from the REST API
 * response surface. Includes nested product information via a
 * ProductDTO reference and a quantity field sourced from the separate
 * Inventory entity for API convenience.
 *
 * Returned by:
 *   GET /api/items?productId={id}              — List<ItemDTO>
 *   GET /api/items/{id}                        — single ItemDTO
 *
 * Consumed by:
 *   Order Service (via CatalogServiceClient) for item details during
 *   order placement and cart operations.
 *   Monolith ActionBeans (CartActionBean, CatalogActionBean) during
 *   the Strangler Fig coexistence window.
 */
package com.jpetstore.catalog.dto;

import java.math.BigDecimal;

/**
 * Data Transfer Object representing an item in the catalog.
 *
 * <p>This DTO carries item data between the Catalog Service REST API
 * and its consumers (API Gateway, Order Service, and the monolith
 * ActionBeans during the Strangler Fig coexistence window).
 *
 * <p>Fields mirror the monolith's {@code org.mybatis.jpetstore.domain.Item}
 * class with the following adaptations for the microservices architecture:
 * <ul>
 *   <li>The JPA entity's {@code @ManyToOne Product} relationship is
 *       represented as a nested {@link ProductDTO} to prevent JPA proxy
 *       leakage into JSON serialization.</li>
 *   <li>The {@code supplierId} is flattened to a primitive {@code int}
 *       rather than a nested SupplierDTO, because the monolith never
 *       exposed supplier details in item views.</li>
 *   <li>The {@code productId} is duplicated as both a top-level
 *       convenience field and available inside {@code product.productId}.</li>
 *   <li>The {@code quantity} field is populated by the service layer
 *       from the separate Inventory entity (inventory.qty), not from
 *       the Item entity directly — reflecting the database-per-service
 *       decomposition where inventory is tracked separately.</li>
 * </ul>
 *
 * <p>No JPA annotations, no validation annotations, no {@code Serializable}
 * implementation — Jackson handles JSON serialization automatically.
 * No business logic (e.g., trimming) is performed here; that responsibility
 * belongs to the entity/service layer.
 */
public class ItemDTO {

    /**
     * Unique item identifier (e.g., "EST-1", "EST-14").
     * Maps from the JPA entity's {@code itemId} field
     * (DB column {@code itemid}, VARCHAR(10)).
     */
    private String itemId;

    /**
     * Identifier of the product this item belongs to (e.g., "FI-SW-01").
     * Flattened from the JPA entity's {@code product.getProductId()} for
     * API consumer convenience. Also available as {@code product.productId}
     * when the nested ProductDTO is populated.
     */
    private String productId;

    /**
     * Retail list price of this item.
     * Maps from the JPA entity's {@code listPrice} field
     * (DB column {@code listprice}, DECIMAL(10,2)).
     * Uses {@link BigDecimal} for exact decimal representation.
     */
    private BigDecimal listPrice;

    /**
     * Wholesale unit cost of this item.
     * Maps from the JPA entity's {@code unitCost} field
     * (DB column {@code unitcost}, DECIMAL(10,2)).
     * Uses {@link BigDecimal} for exact decimal representation.
     */
    private BigDecimal unitCost;

    /**
     * Identifier of the supplier for this item.
     * In the JPA entity this is a {@code @ManyToOne Supplier} relationship;
     * in this DTO it is flattened back to a primitive int because the
     * monolith never exposed supplier details in item views.
     * Maps from DB column {@code supplier} (INTEGER).
     */
    private int supplierId;

    /**
     * Item availability status (e.g., "P" for available).
     * Maps from the JPA entity's {@code status} field
     * (DB column {@code status}, VARCHAR(2)).
     */
    private String status;

    /**
     * First descriptive attribute of this item (e.g., "Large").
     * Maps from the JPA entity's {@code attribute1} field
     * (DB column {@code attr1}, VARCHAR(80)).
     */
    private String attribute1;

    /**
     * Second descriptive attribute of this item (e.g., "Adult Male").
     * Maps from the JPA entity's {@code attribute2} field
     * (DB column {@code attr2}, VARCHAR(80)).
     */
    private String attribute2;

    /**
     * Third descriptive attribute of this item.
     * Maps from the JPA entity's {@code attribute3} field
     * (DB column {@code attr3}, VARCHAR(80)).
     */
    private String attribute3;

    /**
     * Fourth descriptive attribute of this item.
     * Maps from the JPA entity's {@code attribute4} field
     * (DB column {@code attr4}, VARCHAR(80)).
     */
    private String attribute4;

    /**
     * Fifth descriptive attribute of this item.
     * Maps from the JPA entity's {@code attribute5} field
     * (DB column {@code attr5}, VARCHAR(80)).
     */
    private String attribute5;

    /**
     * Nested product information for this item.
     * In the monolith's {@code Item.java}, this was a
     * {@code private Product product} reference. Here we use
     * {@link ProductDTO} to avoid JPA proxy objects leaking into
     * the JSON response. Contains productId, categoryId, name,
     * and description.
     */
    private ProductDTO product;

    /**
     * Current inventory quantity for this item.
     * In the decomposed architecture, this value comes from the
     * separate Inventory entity ({@code inventory.qty}), NOT from
     * the Item entity directly. The service layer fetches it from
     * the Inventory repository and populates it here for API
     * consumer convenience.
     */
    private int quantity;

    // ---------------------------------------------------------------
    // Getters and Setters — standard JavaBean accessors
    // ---------------------------------------------------------------

    /**
     * Returns the unique item identifier.
     *
     * @return the item ID string, e.g., "EST-1"
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets the unique item identifier.
     *
     * @param itemId the item ID string
     */
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /**
     * Returns the product identifier this item belongs to.
     *
     * @return the product ID string, e.g., "FI-SW-01"
     */
    public String getProductId() {
        return productId;
    }

    /**
     * Sets the product identifier this item belongs to.
     *
     * @param productId the product ID string
     */
    public void setProductId(String productId) {
        this.productId = productId;
    }

    /**
     * Returns the retail list price of this item.
     *
     * @return the list price as a {@link BigDecimal}, e.g., 16.50
     */
    public BigDecimal getListPrice() {
        return listPrice;
    }

    /**
     * Sets the retail list price of this item.
     *
     * @param listPrice the list price as a {@link BigDecimal}
     */
    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    /**
     * Returns the wholesale unit cost of this item.
     *
     * @return the unit cost as a {@link BigDecimal}, e.g., 10.00
     */
    public BigDecimal getUnitCost() {
        return unitCost;
    }

    /**
     * Sets the wholesale unit cost of this item.
     *
     * @param unitCost the unit cost as a {@link BigDecimal}
     */
    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    /**
     * Returns the supplier identifier for this item.
     *
     * @return the supplier ID as an int
     */
    public int getSupplierId() {
        return supplierId;
    }

    /**
     * Sets the supplier identifier for this item.
     *
     * @param supplierId the supplier ID as an int
     */
    public void setSupplierId(int supplierId) {
        this.supplierId = supplierId;
    }

    /**
     * Returns the item availability status.
     *
     * @return the status string, e.g., "P"
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the item availability status.
     *
     * @param status the status string
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the first descriptive attribute.
     *
     * @return the attribute1 string, or {@code null} if not set
     */
    public String getAttribute1() {
        return attribute1;
    }

    /**
     * Sets the first descriptive attribute.
     *
     * @param attribute1 the attribute1 string
     */
    public void setAttribute1(String attribute1) {
        this.attribute1 = attribute1;
    }

    /**
     * Returns the second descriptive attribute.
     *
     * @return the attribute2 string, or {@code null} if not set
     */
    public String getAttribute2() {
        return attribute2;
    }

    /**
     * Sets the second descriptive attribute.
     *
     * @param attribute2 the attribute2 string
     */
    public void setAttribute2(String attribute2) {
        this.attribute2 = attribute2;
    }

    /**
     * Returns the third descriptive attribute.
     *
     * @return the attribute3 string, or {@code null} if not set
     */
    public String getAttribute3() {
        return attribute3;
    }

    /**
     * Sets the third descriptive attribute.
     *
     * @param attribute3 the attribute3 string
     */
    public void setAttribute3(String attribute3) {
        this.attribute3 = attribute3;
    }

    /**
     * Returns the fourth descriptive attribute.
     *
     * @return the attribute4 string, or {@code null} if not set
     */
    public String getAttribute4() {
        return attribute4;
    }

    /**
     * Sets the fourth descriptive attribute.
     *
     * @param attribute4 the attribute4 string
     */
    public void setAttribute4(String attribute4) {
        this.attribute4 = attribute4;
    }

    /**
     * Returns the fifth descriptive attribute.
     *
     * @return the attribute5 string, or {@code null} if not set
     */
    public String getAttribute5() {
        return attribute5;
    }

    /**
     * Sets the fifth descriptive attribute.
     *
     * @param attribute5 the attribute5 string
     */
    public void setAttribute5(String attribute5) {
        this.attribute5 = attribute5;
    }

    /**
     * Returns the nested product information for this item.
     *
     * @return the {@link ProductDTO} containing product details,
     *         or {@code null} if not populated
     */
    public ProductDTO getProduct() {
        return product;
    }

    /**
     * Sets the nested product information for this item.
     *
     * @param product the {@link ProductDTO} containing product details
     */
    public void setProduct(ProductDTO product) {
        this.product = product;
    }

    /**
     * Returns the current inventory quantity for this item.
     * This value is populated by the service layer from the
     * separate Inventory entity.
     *
     * @return the inventory quantity
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * Sets the current inventory quantity for this item.
     *
     * @param quantity the inventory quantity
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
