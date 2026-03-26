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
package com.jpetstore.catalog.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jpetstore.catalog.config.SecurityConfig;
import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.service.CatalogService;

/**
 * Unit tests for {@link ProductController} using Spring's {@code @WebMvcTest} web slice testing.
 *
 * <p>Verifies the three REST endpoints exposed by ProductController:</p>
 * <ul>
 *   <li>{@code GET /api/products?categoryId=} — list products by category</li>
 *   <li>{@code GET /api/products/{id}} — get product by ID (including 404 for not found)</li>
 *   <li>{@code GET /api/products/search?keywords=} — keyword search with validation</li>
 * </ul>
 *
 * <p><strong>Entity-to-DTO Mapping Verification</strong>: The tests validate that the
 * controller's {@code toDTO()} method correctly flattens the {@code @ManyToOne Category}
 * relationship on the {@link Product} entity into a flat {@code categoryId} string field
 * in {@code ProductDTO}. Specifically: {@code product.getCategory().getCatId()} →
 * {@code dto.setCategoryId()}.</p>
 *
 * <p><strong>Keyword Validation</strong>: The empty and blank keyword search tests verify
 * that the REST controller returns HTTP 400 Bad Request for invalid keywords, mirroring
 * the monolith's {@code CatalogActionBean.searchProducts()} validation
 * ({@code if (keyword == null || keyword.length() < 1)} → error message).</p>
 *
 * <p>Uses {@code @Import(SecurityConfig.class)} because the catalog-service includes
 * {@code spring-boot-starter-security} on the classpath. Without importing the production
 * SecurityConfig (which permits all requests), Spring Security's default deny-all behavior
 * would cause all requests to return 401 Unauthorized.</p>
 *
 * @see ProductController
 * @see CatalogService
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CatalogService catalogService;

    // ========================================================================
    // GET /api/products?categoryId= — List products by category
    // ========================================================================

    /**
     * Verifies that {@code GET /api/products?categoryId=FISH} returns HTTP 200 OK
     * with a JSON array of {@link com.jpetstore.catalog.dto.ProductDTO} objects.
     *
     * <p>Validates the critical entity→DTO flattening: the Product entity has a
     * {@code @ManyToOne Category category} field, but the DTO exposes a flat
     * {@code String categoryId}. The controller's {@code toDTO()} method performs
     * this mapping via {@code product.getCategory().getCatId()}.</p>
     *
     * <p>Assertions cover: HTTP status, content type, array size, all DTO fields
     * (productId, categoryId, name, description), and service delegation verification.</p>
     */
    @Test
    void shouldReturnProductsByCategory() throws Exception {
        // Arrange: two products in the FISH category
        Product product1 = createProduct("FI-SW-01", "FISH", "Angelfish", "Saltwater species");
        Product product2 = createProduct("FI-SW-02", "FISH", "Tiger Shark", "Saltwater predator");
        when(catalogService.getProductListByCategory("FISH"))
                .thenReturn(Arrays.asList(product1, product2));

        // Act & Assert
        mockMvc.perform(get("/api/products").param("categoryId", "FISH"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // First product: verify all DTO fields including categoryId flattening
                .andExpect(jsonPath("$[0].productId").value("FI-SW-01"))
                .andExpect(jsonPath("$[0].categoryId").value("FISH"))
                .andExpect(jsonPath("$[0].name").value("Angelfish"))
                .andExpect(jsonPath("$[0].description").value("Saltwater species"))
                // Second product: verify productId
                .andExpect(jsonPath("$[1].productId").value("FI-SW-02"));

        // Verify service delegation
        verify(catalogService).getProductListByCategory("FISH");
    }

    /**
     * Verifies that {@code GET /api/products?categoryId=EMPTY} returns HTTP 200 OK
     * with an empty JSON array when the category has no products.
     *
     * <p>Returns 200 (NOT 404) for an empty list — matching the monolith's behavior
     * where {@code productMapper.getProductListByCategory()} returned an empty list
     * for non-existent or empty categories.</p>
     */
    @Test
    void shouldReturnEmptyListWhenCategoryHasNoProducts() throws Exception {
        // Arrange: category with no products
        when(catalogService.getProductListByCategory("EMPTY"))
                .thenReturn(Collections.emptyList());

        // Act & Assert: returns 200 with empty array, NOT 404
        mockMvc.perform(get("/api/products").param("categoryId", "EMPTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        // Verify service was still called
        verify(catalogService).getProductListByCategory("EMPTY");
    }

    // ========================================================================
    // GET /api/products/{id} — Get product by ID
    // ========================================================================

    /**
     * Verifies that {@code GET /api/products/FI-SW-01} returns HTTP 200 OK with a
     * single {@link com.jpetstore.catalog.dto.ProductDTO} containing all fields.
     *
     * <p>Assertions verify all four DTO fields: {@code productId}, {@code categoryId}
     * (flattened from {@code product.getCategory().getCatId()}), {@code name}, and
     * {@code description}.</p>
     */
    @Test
    void shouldReturnProductById() throws Exception {
        // Arrange: single product with nested Category entity
        Product product = createProduct("FI-SW-01", "FISH", "Angelfish", "Saltwater species");
        when(catalogService.getProduct("FI-SW-01")).thenReturn(product);

        // Act & Assert
        mockMvc.perform(get("/api/products/{id}", "FI-SW-01"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productId").value("FI-SW-01"))
                .andExpect(jsonPath("$.categoryId").value("FISH"))
                .andExpect(jsonPath("$.name").value("Angelfish"))
                .andExpect(jsonPath("$.description").value("Saltwater species"));

        // Verify service delegation
        verify(catalogService).getProduct("FI-SW-01");
    }

    /**
     * Verifies that {@code GET /api/products/NONEXISTENT} returns HTTP 404 Not Found
     * when the service returns {@code null} (no product found).
     *
     * <p>This matches the monolith's behavior where the MyBatis mapper returned
     * {@code null} for a non-existent product ID.</p>
     */
    @Test
    void shouldReturn404WhenProductNotFound() throws Exception {
        // Arrange: service returns null for nonexistent product
        when(catalogService.getProduct("NONEXISTENT")).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/api/products/{id}", "NONEXISTENT"))
                .andExpect(status().isNotFound());

        // Verify service was still called
        verify(catalogService).getProduct("NONEXISTENT");
    }

    // ========================================================================
    // GET /api/products/search?keywords= — Keyword search
    // ========================================================================

    /**
     * Verifies that {@code GET /api/products/search?keywords=angel} returns HTTP 200 OK
     * with matching products in a JSON array.
     *
     * <p>The keyword is passed through to the service as-is because the service
     * handles lowercasing and tokenization internally (CatalogService line 74:
     * {@code keyword.toLowerCase()}). This preserves identical search behavior
     * with the monolith.</p>
     */
    @Test
    void shouldSearchProducts() throws Exception {
        // Arrange: search for "angel" returns matching product
        Product product = createProduct("FI-SW-01", "FISH", "Angelfish", "Saltwater species");
        when(catalogService.searchProductList("angel"))
                .thenReturn(Collections.singletonList(product));

        // Act & Assert
        mockMvc.perform(get("/api/products/search").param("keywords", "angel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value("FI-SW-01"))
                .andExpect(jsonPath("$[0].name").value("Angelfish"));

        // Verify keyword is passed through to the service without transformation
        verify(catalogService).searchProductList("angel");
    }

    /**
     * Verifies that {@code GET /api/products/search?keywords=} (empty keywords)
     * returns HTTP 400 Bad Request.
     *
     * <p>Mirrors the monolith's {@code CatalogActionBean.searchProducts()} validation
     * (lines 191-193): {@code if (keyword == null || keyword.length() < 1)} showed
     * error message. In the REST controller, this translates to 400 Bad Request.</p>
     *
     * <p>The service must NOT be called when validation fails — confirmed via
     * {@code verify(never())}.</p>
     */
    @Test
    void shouldReturn400ForEmptyKeywordSearch() throws Exception {
        // Act & Assert: empty keywords parameter → 400 Bad Request
        mockMvc.perform(get("/api/products/search").param("keywords", ""))
                .andExpect(status().isBadRequest());

        // Service should NOT be called when validation fails
        verify(catalogService, never()).searchProductList(anyString());
    }

    /**
     * Verifies that {@code GET /api/products/search?keywords=%20%20} (whitespace-only)
     * also returns HTTP 400 Bad Request.
     *
     * <p>The controller checks {@code keywords.trim().isEmpty()} which catches both
     * empty strings and whitespace-only strings. This prevents meaningless searches
     * that would tokenize into zero keywords.</p>
     */
    @Test
    void shouldReturn400ForBlankKeywordSearch() throws Exception {
        // Act & Assert: whitespace-only keywords → 400 Bad Request
        mockMvc.perform(get("/api/products/search").param("keywords", "   "))
                .andExpect(status().isBadRequest());

        // Service should NOT be called for blank keywords
        verify(catalogService, never()).searchProductList(anyString());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a {@link Product} entity with a nested {@link Category} entity,
     * mirroring the JPA {@code @ManyToOne} relationship between Product and Category.
     *
     * <p>The controller's {@code toDTO()} method flattens
     * {@code product.getCategory().getCatId()} into {@code ProductDTO.categoryId},
     * so this helper must construct the full entity relationship graph.</p>
     *
     * @param productId   the product identifier (e.g., "FI-SW-01")
     * @param categoryId  the category identifier for the nested Category entity (e.g., "FISH")
     * @param name        the product name (e.g., "Angelfish")
     * @param description the product description (e.g., "Saltwater species")
     * @return a fully constructed Product entity with nested Category
     */
    private Product createProduct(String productId, String categoryId, String name, String description) {
        Category category = new Category();
        category.setCatId(categoryId);

        Product product = new Product();
        product.setProductId(productId);
        product.setCategory(category);
        product.setName(name);
        product.setDescription(description);
        return product;
    }
}
