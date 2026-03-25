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

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jpetstore.catalog.dto.CategoryDTO;
import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.service.CatalogService;

/**
 * REST controller for the Category resource in the Catalog bounded context.
 *
 * <p>Exposes category browsing endpoints that replicate the monolith's
 * {@code CatalogActionBean} category operations via REST API.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/categories} — List all categories</li>
 *   <li>{@code GET /api/categories/{id}} — Get a single category by ID</li>
 * </ul>
 *
 * <p>These endpoints replace the monolith's Stripes action paths:
 * {@code /actions/Catalog.action?viewCategory&categoryId=FISH}.</p>
 *
 * @see com.jpetstore.catalog.service.CatalogService
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private static final Logger log = LoggerFactory.getLogger(CategoryController.class);

    private final CatalogService catalogService;

    /**
     * Constructs the CategoryController with the required CatalogService.
     *
     * @param catalogService the business logic service for catalog operations
     */
    public CategoryController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Retrieves all categories in the catalog.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewMain()} which calls
     * {@code catalogService.getCategoryList()} and renders the category list
     * in Main.jsp.</p>
     *
     * @return HTTP 200 with a JSON array of {@link CategoryDTO} objects
     */
    @GetMapping
    public ResponseEntity<List<CategoryDTO>> getAllCategories() {
        log.debug("GET /api/categories — fetching all categories");
        List<Category> categories = catalogService.getCategoryList();
        List<CategoryDTO> dtos = categories.stream()
                .map(CategoryController::toDTO)
                .collect(Collectors.toList());
        log.debug("Returning {} categories", dtos.size());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves a single category by its unique identifier.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewCategory()} which calls
     * {@code catalogService.getCategory(categoryId)} and renders Category.jsp.</p>
     *
     * @param id the category identifier (e.g., "FISH", "DOGS", "CATS", "REPTILES", "BIRDS")
     * @return HTTP 200 with the {@link CategoryDTO} if found;
     *         HTTP 404 if no category exists with the given ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<CategoryDTO> getCategoryById(@PathVariable String id) {
        log.debug("GET /api/categories/{} — fetching category", id);
        Category category = catalogService.getCategory(id);
        if (category != null) {
            log.debug("Category found: {}", id);
            return ResponseEntity.ok(toDTO(category));
        } else {
            log.debug("Category not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Converts a Category entity to a CategoryDTO for API responses.
     *
     * <p>Maps entity field names (column-aligned: {@code catId}, {@code descn}) to
     * DTO field names (API-aligned: {@code categoryId}, {@code description}).</p>
     *
     * @param category the entity to convert (must not be null)
     * @return the populated CategoryDTO
     */
    private static CategoryDTO toDTO(Category category) {
        CategoryDTO dto = new CategoryDTO();
        dto.setCategoryId(category.getCatId());
        dto.setName(category.getName());
        dto.setDescription(category.getDescription());
        return dto;
    }
}
