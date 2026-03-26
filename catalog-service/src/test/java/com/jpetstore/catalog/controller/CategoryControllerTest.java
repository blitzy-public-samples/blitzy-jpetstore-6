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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jpetstore.catalog.config.SecurityConfig;
import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.service.CatalogService;

/**
 * Unit tests for {@link CategoryController} using Spring's {@code @WebMvcTest} web slice testing.
 *
 * <p>Verifies the two REST endpoints exposed by CategoryController:</p>
 * <ul>
 *   <li>{@code GET /api/categories} — list all categories</li>
 *   <li>{@code GET /api/categories/{id}} — get category by ID (including 404 for not found)</li>
 * </ul>
 *
 * <p><strong>Entity-to-DTO Mapping Verification</strong>: The tests validate that the
 * controller's {@code toDTO()} method correctly maps the JPA entity field {@code catId}
 * to the DTO field {@code categoryId}. Specifically: {@code category.getCatId()} →
 * {@code dto.setCategoryId()}. The {@code jsonPath("$.categoryId")} assertions verify
 * this critical name translation at the HTTP response level.</p>
 *
 * <p><strong>Monolith Equivalence</strong>: This test class replaces the behavioral
 * verification from the monolith's {@code CatalogServiceTest.shouldReturnCategoryList()}
 * and {@code CatalogServiceTest.shouldReturnCategory()} methods (lines 72-98 of the
 * original test), upgrading them to HTTP-level assertions via MockMvc.</p>
 *
 * <p>Uses {@code @Import(SecurityConfig.class)} because the catalog-service includes
 * {@code spring-boot-starter-security} on the classpath. Without importing the production
 * SecurityConfig (which permits all requests and disables CSRF), Spring Security's default
 * deny-all behavior would cause all requests to return 401 Unauthorized.</p>
 *
 * @see CategoryController
 * @see CatalogService
 * @see com.jpetstore.catalog.dto.CategoryDTO
 */
@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
class CategoryControllerTest {

    /**
     * Auto-configured MockMvc instance for performing HTTP request simulations
     * against the CategoryController endpoints without starting a real server.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mockito mock bean replacing the real CatalogService in the web slice context.
     * Test methods configure mock behavior via {@code when()} and verify delegation
     * via {@code verify()}. This is the only dependency of CategoryController.
     */
    @MockBean
    private CatalogService catalogService;

    // ========================================================================
    // GET /api/categories — List all categories
    // ========================================================================

    /**
     * Verifies that {@code GET /api/categories} returns HTTP 200 OK with a JSON array
     * of {@link com.jpetstore.catalog.dto.CategoryDTO} objects when categories exist.
     *
     * <p>Validates the critical entity→DTO name mapping: the Category JPA entity
     * field is {@code catId} (mapped to DB column {@code catid}), but the DTO and
     * JSON response use {@code categoryId}. The controller's {@code toDTO()} method
     * performs this mapping via {@code category.getCatId()} → {@code dto.setCategoryId()}.
     * The {@code jsonPath("$[0].categoryId")} assertion confirms this mapping works.</p>
     *
     * <p>Assertions cover: HTTP status, content type, array size, all DTO fields
     * ({@code categoryId}, {@code name}, {@code description}) for both categories,
     * and service delegation verification.</p>
     *
     * <p>Monolith equivalence: Replaces {@code CatalogServiceTest.shouldReturnCategoryList()}
     * (source lines 72-83) but at the HTTP level.</p>
     */
    @Test
    void shouldReturnAllCategories() throws Exception {
        // Arrange: two categories using entity field name 'catId' (not 'categoryId')
        Category fish = createCategory("FISH", "Fish", "Saltwater, Freshwater");
        Category dogs = createCategory("DOGS", "Dogs", "Various Breeds");
        when(catalogService.getCategoryList()).thenReturn(Arrays.asList(fish, dogs));

        // Act & Assert
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // First category: verify all DTO fields — 'categoryId' NOT 'catId'
                .andExpect(jsonPath("$[0].categoryId").value("FISH"))
                .andExpect(jsonPath("$[0].name").value("Fish"))
                .andExpect(jsonPath("$[0].description").value("Saltwater, Freshwater"))
                // Second category: verify all DTO fields
                .andExpect(jsonPath("$[1].categoryId").value("DOGS"))
                .andExpect(jsonPath("$[1].name").value("Dogs"))
                .andExpect(jsonPath("$[1].description").value("Various Breeds"));

        // Verify service delegation happened exactly once
        verify(catalogService).getCategoryList();
    }

    /**
     * Verifies that {@code GET /api/categories} returns HTTP 200 OK with an empty JSON
     * array when no categories exist in the catalog.
     *
     * <p>Returns 200 (NOT 404) for an empty list — matching the monolith's behavior
     * where {@code categoryMapper.getCategoryList()} returned an empty list when no
     * categories existed.</p>
     */
    @Test
    void shouldReturnEmptyListWhenNoCategories() throws Exception {
        // Arrange: service returns empty list
        when(catalogService.getCategoryList()).thenReturn(Collections.emptyList());

        // Act & Assert: returns 200 with empty array, NOT 404
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        // Verify service was still called
        verify(catalogService).getCategoryList();
    }

    // ========================================================================
    // GET /api/categories/{id} — Get category by ID
    // ========================================================================

    /**
     * Verifies that {@code GET /api/categories/FISH} returns HTTP 200 OK with a single
     * {@link com.jpetstore.catalog.dto.CategoryDTO} JSON object when the category exists.
     *
     * <p>Assertions verify all three DTO fields: {@code categoryId} (mapped from entity
     * {@code catId}), {@code name}, and {@code description}. The {@code $.categoryId}
     * assertion confirms the entity→DTO name mapping works correctly for the single
     * object response.</p>
     *
     * <p>Monolith equivalence: Replaces {@code CatalogServiceTest.shouldReturnCategory()}
     * (source lines 85-98) but at the HTTP level.</p>
     */
    @Test
    void shouldReturnCategoryById() throws Exception {
        // Arrange: single category
        Category fish = createCategory("FISH", "Fish", "Saltwater, Freshwater");
        when(catalogService.getCategory("FISH")).thenReturn(fish);

        // Act & Assert
        mockMvc.perform(get("/api/categories/{id}", "FISH"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Verify entity→DTO name mapping: catId → categoryId
                .andExpect(jsonPath("$.categoryId").value("FISH"))
                .andExpect(jsonPath("$.name").value("Fish"))
                .andExpect(jsonPath("$.description").value("Saltwater, Freshwater"));

        // Verify service delegation with the exact ID passed in the URL path
        verify(catalogService).getCategory("FISH");
    }

    /**
     * Verifies that {@code GET /api/categories/NONEXISTENT} returns HTTP 404 Not Found
     * when the service returns {@code null} (no category found for the given ID).
     *
     * <p>This matches the monolith's behavior where the MyBatis mapper returned
     * {@code null} for a non-existent category ID, and the controller translates
     * this to a 404 response via {@code ResponseEntity.notFound().build()}.</p>
     */
    @Test
    void shouldReturn404WhenCategoryNotFound() throws Exception {
        // Arrange: service returns null for nonexistent category
        when(catalogService.getCategory("NONEXISTENT")).thenReturn(null);

        // Act & Assert: 404 Not Found
        mockMvc.perform(get("/api/categories/{id}", "NONEXISTENT"))
                .andExpect(status().isNotFound());

        // Verify service was still called with the nonexistent ID
        verify(catalogService).getCategory("NONEXISTENT");
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Creates a {@link Category} JPA entity with the specified field values.
     *
     * <p>Uses the entity's setter methods ({@code setCatId()}, {@code setName()},
     * {@code setDescription()}) to populate test data. Note: the entity field is
     * {@code catId} (mapped to DB column {@code catid}), NOT {@code categoryId} —
     * the DTO translation to {@code categoryId} happens in the controller's
     * {@code toDTO()} method.</p>
     *
     * @param catId       the category identifier (e.g., "FISH", "DOGS")
     * @param name        the display name (e.g., "Fish", "Dogs")
     * @param description the category description
     * @return a populated Category entity ready for use in mock return values
     */
    private Category createCategory(String catId, String name, String description) {
        Category category = new Category();
        category.setCatId(catId);
        category.setName(name);
        category.setDescription(description);
        return category;
    }
}
