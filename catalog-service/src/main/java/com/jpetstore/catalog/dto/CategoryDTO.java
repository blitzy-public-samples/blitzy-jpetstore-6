package com.jpetstore.catalog.dto;

/**
 * Data Transfer Object for Category entities in REST API responses.
 *
 * <p>This DTO decouples the {@code Category} JPA entity from the REST API
 * response surface, ensuring that JPA-specific annotations ({@code @Entity},
 * {@code @Id}, {@code @Column}) and potential lazy-loading proxies never leak
 * into JSON responses serialized by Jackson.</p>
 *
 * <h3>Field Mapping</h3>
 * <ul>
 *   <li>{@code categoryId} — maps from JPA entity {@code catId} (DB column {@code catid}).
 *       The DTO uses the API-friendly name {@code categoryId} for consumer clarity,
 *       matching the original monolith's {@code Category.categoryId} field.</li>
 *   <li>{@code name} — maps directly from entity {@code name} (DB column {@code name}).</li>
 *   <li>{@code description} — maps from entity {@code description} (DB column {@code descn}).</li>
 * </ul>
 *
 * <h3>REST API Usage</h3>
 * <ul>
 *   <li>{@code GET /api/categories} — returns {@code List<CategoryDTO>}</li>
 *   <li>{@code GET /api/categories/{id}} — returns a single {@code CategoryDTO}</li>
 * </ul>
 *
 * <p>This is a pure data carrier with no business logic, no validation annotations
 * (response DTO only), and no framework dependencies.</p>
 *
 * @see com.jpetstore.catalog.controller.CategoryController
 */
public class CategoryDTO {

    /**
     * Human-readable category identifier (e.g., "FISH", "DOGS", "CATS", "REPTILES", "BIRDS").
     * Mapped from the JPA entity's {@code catId} field via the service layer:
     * {@code entity.getCatId() → dto.setCategoryId()}.
     */
    private String categoryId;

    /**
     * Display name of the category (e.g., "Fish", "Dogs").
     * VARCHAR(80) in the database.
     */
    private String name;

    /**
     * Descriptive text for the category.
     * VARCHAR(255) in the database, mapped from DB column {@code descn}.
     */
    private String description;

    /**
     * Returns the category identifier.
     *
     * @return the category ID string (e.g., "FISH", "DOGS")
     */
    public String getCategoryId() {
        return categoryId;
    }

    /**
     * Sets the category identifier.
     *
     * @param categoryId the category ID string to set
     */
    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * Returns the display name of the category.
     *
     * @return the category name (e.g., "Fish", "Dogs")
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
     * Returns the category description.
     *
     * @return the description text
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the category description.
     *
     * @param description the description text to set
     */
    public void setDescription(String description) {
        this.description = description;
    }
}
