/*
 * ProductDTO.java — REST Response DTO for Products (Catalog Service)
 *
 * Plain POJO that decouples the Product JPA entity from the REST API
 * response surface. Flattens the @ManyToOne Category relationship
 * back to a simple categoryId String field for API clarity.
 *
 * Returned by:
 *   GET /api/products?categoryId={id}        — List<ProductDTO>
 *   GET /api/products/{id}                   — single ProductDTO
 *   GET /api/products/search?keywords={kw}   — List<ProductDTO>
 *
 * Also nested inside ItemDTO as the "product" field for:
 *   GET /api/items?productId={id}            — List<ItemDTO>
 *   GET /api/items/{id}                      — single ItemDTO
 *
 * Cross-service usage:
 *   Account Service calls GET /api/products?categoryId={favCategoryId}
 *   for personalization (myList). Fallback is an empty list if the
 *   Catalog Service is unavailable.
 */
package com.jpetstore.catalog.dto;

/**
 * Data Transfer Object representing a product in the catalog.
 *
 * <p>This DTO carries product data between the Catalog Service REST API
 * and its consumers (API Gateway, Account Service, Order Service, and
 * the monolith ActionBeans during the Strangler Fig coexistence window).
 *
 * <p>Fields mirror the monolith's {@code org.mybatis.jpetstore.domain.Product}
 * class. The JPA entity's {@code @ManyToOne Category} relationship is
 * flattened back to a plain {@code categoryId} String so that API consumers
 * receive a simple, self-contained representation without nested entities.
 *
 * <p>No JPA annotations, no validation annotations, no {@code Serializable}
 * implementation — Jackson handles JSON serialization automatically.
 * No business logic (e.g., trimming) is performed here; that responsibility
 * belongs to the entity/service layer.
 */
public class ProductDTO {

    /**
     * Unique product identifier (e.g., "FI-SW-01", "K9-BD-01").
     * Maps from the JPA entity's {@code productId} field
     * (DB column {@code productid}).
     */
    private String productId;

    /**
     * Identifier of the category this product belongs to (e.g., "FISH", "DOGS").
     * The JPA entity stores this as a {@code @ManyToOne Category} relationship;
     * this DTO flattens it back to a plain String via
     * {@code entity.getCategory().getCatId()}.
     */
    private String categoryId;

    /**
     * Human-readable product name (e.g., "Angelfish", "Tiger Shark").
     * VARCHAR(80) in the database.
     */
    private String name;

    /**
     * Product description text.
     * VARCHAR(255) in the database (DB column {@code descn}).
     */
    private String description;

    /**
     * Returns the unique product identifier.
     *
     * @return the product ID string, e.g., "FI-SW-01"
     */
    public String getProductId() {
        return productId;
    }

    /**
     * Sets the unique product identifier.
     *
     * @param productId the product ID string
     */
    public void setProductId(String productId) {
        this.productId = productId;
    }

    /**
     * Returns the category identifier this product belongs to.
     *
     * @return the category ID string, e.g., "FISH"
     */
    public String getCategoryId() {
        return categoryId;
    }

    /**
     * Sets the category identifier this product belongs to.
     *
     * @param categoryId the category ID string
     */
    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * Returns the human-readable product name.
     *
     * @return the product name, e.g., "Angelfish"
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the human-readable product name.
     *
     * @param name the product name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the product description.
     *
     * @return the description text
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the product description.
     *
     * @param description the description text
     */
    public void setDescription(String description) {
        this.description = description;
    }
}
