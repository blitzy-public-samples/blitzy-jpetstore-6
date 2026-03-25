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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jpetstore.catalog.dto.ProductDTO;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.service.CatalogService;

/**
 * REST controller for the Product resource in the Catalog bounded context.
 *
 * <p>Exposes product browsing and search endpoints that replicate the monolith's
 * {@code CatalogActionBean} product operations via REST API.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/products?categoryId=} — List products by category</li>
 *   <li>{@code GET /api/products/{id}} — Get a single product by ID</li>
 *   <li>{@code GET /api/products/search?keywords=} — Search products by keyword(s)</li>
 * </ul>
 *
 * <p>These endpoints replace the monolith's Stripes action paths:
 * {@code /actions/Catalog.action?viewProduct&productId=FI-SW-01} and
 * {@code /actions/Catalog.action?searchProducts&keyword=fish}.</p>
 *
 * @see com.jpetstore.catalog.service.CatalogService
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final CatalogService catalogService;

    /**
     * Constructs the ProductController with the required CatalogService.
     *
     * @param catalogService the business logic service for catalog operations
     */
    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Retrieves all products belonging to a specified category.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewCategory()} which calls
     * {@code catalogService.getProductListByCategory(categoryId)} and populates
     * the product list displayed in Category.jsp.</p>
     *
     * @param categoryId the category identifier to filter products by
     *                   (e.g., "FISH", "DOGS", "CATS")
     * @return HTTP 200 with a JSON array of {@link ProductDTO} objects;
     *         empty array if no products match the category
     */
    @GetMapping(params = "categoryId")
    public ResponseEntity<List<ProductDTO>> getProductsByCategory(
            @RequestParam String categoryId) {
        log.debug("GET /api/products?categoryId={} — fetching products by category", categoryId);
        List<Product> products = catalogService.getProductListByCategory(categoryId);
        List<ProductDTO> dtos = products.stream()
                .map(ProductController::toDTO)
                .collect(Collectors.toList());
        log.debug("Returning {} products for category '{}'", dtos.size(), categoryId);
        return ResponseEntity.ok(dtos);
    }

    /**
     * Searches for products whose names match any of the provided keywords.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.searchProducts()} which calls
     * {@code catalogService.searchProductList(keyword)} with space-separated keywords.
     * The underlying service tokenizes by whitespace, lowercases each token,
     * wraps with '%' wildcards, and runs individual LIKE queries per token,
     * deduplicating results.</p>
     *
     * @param keywords a space-separated string of search keywords
     *                 (e.g., "angel", "angel fish", "bulldog")
     * @return HTTP 200 with a JSON array of matching {@link ProductDTO} objects;
     *         empty array if no products match or keywords is blank
     */
    @GetMapping("/search")
    public ResponseEntity<List<ProductDTO>> searchProducts(
            @RequestParam(defaultValue = "") String keywords) {
        log.debug("GET /api/products/search?keywords={} — searching products", keywords);
        List<Product> products = catalogService.searchProductList(keywords);
        List<ProductDTO> dtos = products.stream()
                .map(ProductController::toDTO)
                .collect(Collectors.toList());
        log.debug("Search for '{}' returned {} products", keywords, dtos.size());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves a single product by its unique identifier.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewProduct()} which calls
     * {@code catalogService.getProduct(productId)} and renders Product.jsp.</p>
     *
     * @param id the product identifier (e.g., "FI-SW-01", "K9-BD-01")
     * @return HTTP 200 with the {@link ProductDTO} if found;
     *         HTTP 404 if no product exists with the given ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProductById(@PathVariable String id) {
        log.debug("GET /api/products/{} — fetching product", id);
        return catalogService.getProduct(id)
                .map(product -> {
                    log.debug("Product found: {}", id);
                    return ResponseEntity.ok(toDTO(product));
                })
                .orElseGet(() -> {
                    log.debug("Product not found: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Converts a Product entity to a ProductDTO for API responses.
     *
     * <p>Maps entity field names (column-aligned) to DTO field names (API-aligned).
     * The category ID is extracted from the associated Category entity's
     * {@code catId} field.</p>
     *
     * @param product the entity to convert (must not be null)
     * @return the populated ProductDTO
     */
    private static ProductDTO toDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setProductId(product.getProductId());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        // Extract category ID from the associated Category entity
        if (product.getCategory() != null) {
            dto.setCategoryId(product.getCategory().getCatId());
        }
        return dto;
    }
}
