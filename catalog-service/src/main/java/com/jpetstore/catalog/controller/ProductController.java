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
 * REST controller exposing the Product resource within the Catalog/Inventory bounded context.
 *
 * <p>This controller replaces the monolith's {@code CatalogActionBean} product operations
 * with clean REST APIs:</p>
 * <ul>
 *   <li>{@code CatalogActionBean.viewCategory()} → {@link #getProductsByCategory(String)}</li>
 *   <li>{@code CatalogActionBean.viewProduct()} → {@link #getProductById(String)}</li>
 *   <li>{@code CatalogActionBean.searchProducts()} → {@link #searchProducts(String)}</li>
 * </ul>
 *
 * <h3>API Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/products?categoryId=} — List products by category</li>
 *   <li>{@code GET /api/products/{id}} — Get a single product by ID</li>
 *   <li>{@code GET /api/products/search?keywords=} — Search products by keyword(s)</li>
 * </ul>
 *
 * <h3>Cross-Service Consumers</h3>
 * <ul>
 *   <li><strong>API Gateway</strong>: Routes {@code GET /api/products*} to this service</li>
 *   <li><strong>Updated Monolith CatalogActionBean</strong>: Calls all three endpoints via REST
 *       during the Strangler Fig coexistence window</li>
 *   <li><strong>Account Service</strong>: Calls {@code GET /api/products?categoryId={favCategoryId}}
 *       for personalization (Section 0.7.4 of AAP); fallback to empty list on 503</li>
 * </ul>
 *
 * <h3>Design Rules</h3>
 * <ul>
 *   <li>Stateless REST controller — no session state, no Stripes ActionBean patterns</li>
 *   <li>Returns DTOs (not JPA entities) for clean API boundaries</li>
 *   <li>No {@code @Transactional} — read-only operations handled at service/repository level</li>
 *   <li>No cross-service database access</li>
 *   <li>Constructor injection (no {@code @Autowired})</li>
 *   <li>Jakarta EE 10 namespace (Spring Boot 3.5.x)</li>
 * </ul>
 *
 * @see CatalogService
 * @see ProductDTO
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final CatalogService catalogService;

    /**
     * Constructs the ProductController with the required CatalogService dependency.
     *
     * <p>Uses constructor injection following Spring best practices.
     * The single-constructor pattern means no {@code @Autowired} annotation is required.</p>
     *
     * @param catalogService the business logic service for catalog read operations;
     *                       provides getProductListByCategory, getProduct, and searchProductList
     */
    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Retrieves all products belonging to a specified category.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewCategory()} (lines 153-158)
     * which calls {@code catalogService.getProductListByCategory(categoryId)} and populates
     * the product list displayed in {@code Category.jsp}.</p>
     *
     * <p>This endpoint is also called by Account Service for personalization
     * ({@code GET /api/products?categoryId={favCategoryId}}). If this Catalog Service is
     * unavailable, the Account Service falls back to an empty list — no error is shown
     * to the user; the personalization section simply appears empty.</p>
     *
     * <p>Returns an empty list (not 404) when the category has no products or when
     * the category ID does not exist. This matches the monolith's behavior where
     * {@code productMapper.getProductListByCategory()} returned an empty list for
     * non-existent categories.</p>
     *
     * @param categoryId the category identifier to filter products by
     *                   (e.g., "FISH", "DOGS", "CATS", "REPTILES", "BIRDS")
     * @return HTTP 200 with a JSON array of {@link ProductDTO} objects;
     *         empty array if no products match the category
     */
    @GetMapping
    public ResponseEntity<List<ProductDTO>> getProductsByCategory(
            @RequestParam("categoryId") String categoryId) {
        log.debug("GET /api/products?categoryId={} — fetching products by category", categoryId);
        List<Product> products = catalogService.getProductListByCategory(categoryId);
        List<ProductDTO> dtos = products.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        log.debug("Returning {} products for category '{}'", dtos.size(), categoryId);
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves a single product by its unique identifier.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewProduct()} (lines 166-171)
     * which calls {@code catalogService.getProduct(productId)} and renders
     * {@code Product.jsp}. In the monolith, this method also called
     * {@code getItemListByProduct()} — in the REST decomposition, items are served
     * by a separate {@code ItemController}.</p>
     *
     * <p>Returns 404 when no product is found, matching the monolith's nullable return
     * behavior from the underlying MyBatis mapper.</p>
     *
     * @param productId the product identifier (e.g., "FI-SW-01", "K9-BD-01")
     * @return HTTP 200 with the {@link ProductDTO} if found;
     *         HTTP 404 if no product exists with the given ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProductById(@PathVariable("id") String productId) {
        log.debug("GET /api/products/{} — fetching product by ID", productId);
        Product product = catalogService.getProduct(productId);
        if (product == null) {
            log.debug("Product not found: {}", productId);
            return ResponseEntity.notFound().build();
        }
        log.debug("Product found: {}", productId);
        return ResponseEntity.ok(toDTO(product));
    }

    /**
     * Searches for products whose names match any of the provided keywords.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.searchProducts()} (lines 190-198)
     * which validates keywords (empty check → error message) then calls
     * {@code catalogService.searchProductList(keyword.toLowerCase())}.</p>
     *
     * <p><strong>Keyword validation</strong>: The monolith's ActionBean (lines 191-193)
     * showed an error message for empty keywords:
     * {@code "Please enter a keyword to search for, then press the search button."}.
     * In the REST API, this is translated to HTTP 400 Bad Request for null or blank
     * keywords, preserving the same validation boundary.</p>
     *
     * <p><strong>Lowercasing behavior</strong>: The monolith ActionBean called
     * {@code keyword.toLowerCase()} before passing to the service (line 195), but the
     * service ALSO lowercases each token internally (CatalogService line 74:
     * {@code keyword.toLowerCase()}). The controller passes keywords as-is since the
     * service handles all lowercasing, preserving identical search behavior.</p>
     *
     * <p><strong>ZERO CHANGE to search logic</strong>: The service replicates the
     * monolith's exact tokenization: {@code keywords.split("\\s+")}, lowercase each
     * token, wrap with {@code "%"}, aggregate via {@code addAll()}. No deduplication
     * is performed — matching the monolith behavior.</p>
     *
     * @param keywords a space-separated string of search keywords
     *                 (e.g., "angel", "angel fish", "bulldog")
     * @return HTTP 200 with a JSON array of matching {@link ProductDTO} objects;
     *         empty array if no products match; HTTP 400 if keywords is null or blank
     */
    @GetMapping("/search")
    public ResponseEntity<List<ProductDTO>> searchProducts(
            @RequestParam("keywords") String keywords) {
        if (keywords == null || keywords.trim().isEmpty()) {
            log.debug("GET /api/products/search — rejected: empty or blank keywords");
            return ResponseEntity.badRequest().build();
        }
        log.debug("GET /api/products/search?keywords={} — searching products", keywords);
        List<Product> products = catalogService.searchProductList(keywords);
        List<ProductDTO> dtos = products.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        log.debug("Search for '{}' returned {} products", keywords, dtos.size());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Converts a Product JPA entity to a ProductDTO for API responses.
     *
     * <p>Performs pure structural transformation with no business logic.
     * The {@code @ManyToOne Category} relationship on the entity is flattened back
     * to a simple {@code categoryId} String, matching the monolith's original flat
     * data structure in {@code org.mybatis.jpetstore.domain.Product}.</p>
     *
     * <p>Null-safe: handles the case where {@code product.getCategory()} returns
     * {@code null} (e.g., if lazy loading fails or category is not yet set).
     * In that case, {@code categoryId} is set to {@code null} in the DTO.</p>
     *
     * <p>Field mapping:</p>
     * <ul>
     *   <li>{@code product.getProductId()} → {@code dto.setProductId()}</li>
     *   <li>{@code product.getCategory().getCatId()} → {@code dto.setCategoryId()} (null-safe)</li>
     *   <li>{@code product.getName()} → {@code dto.setName()}</li>
     *   <li>{@code product.getDescription()} → {@code dto.setDescription()}</li>
     * </ul>
     *
     * @param product the JPA entity to convert; must not be {@code null}
     * @return a fully populated {@link ProductDTO} with all 4 fields set
     */
    private ProductDTO toDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setProductId(product.getProductId());
        dto.setCategoryId(product.getCategory() != null ? product.getCategory().getCatId() : null);
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        return dto;
    }
}
