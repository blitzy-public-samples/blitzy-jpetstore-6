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
package com.jpetstore.catalog.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Inventory;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.repository.CategoryRepository;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.ItemRepository;
import com.jpetstore.catalog.repository.ProductRepository;

/**
 * Business logic service for the Catalog bounded context, replicating the
 * monolith's {@code org.mybatis.jpetstore.service.CatalogService} behavior
 * using Spring Data JPA repositories against a PostgreSQL database.
 *
 * <p>This service handles all read-only catalog operations: category listing,
 * product browsing by category, product search by keywords, item listing by
 * product, individual item retrieval, and inventory stock checks.</p>
 *
 * <h3>Monolith Method Mapping</h3>
 * <table>
 *   <caption>Method mapping from monolith CatalogService to microservice CatalogService</caption>
 *   <tr><th>Monolith Method</th><th>Microservice Method</th></tr>
 *   <tr><td>getCategoryList()</td><td>getCategoryList()</td></tr>
 *   <tr><td>getCategory(String)</td><td>getCategory(String)</td></tr>
 *   <tr><td>getProduct(String)</td><td>getProduct(String)</td></tr>
 *   <tr><td>getProductListByCategory(String)</td><td>getProductListByCategory(String)</td></tr>
 *   <tr><td>searchProductList(String)</td><td>searchProductList(String)</td></tr>
 *   <tr><td>getItemListByProduct(String)</td><td>getItemListByProduct(String)</td></tr>
 *   <tr><td>getItem(String)</td><td>getItem(String)</td></tr>
 *   <tr><td>isItemInStock(String)</td><td>isItemInStock(String)</td></tr>
 * </table>
 *
 * <p><strong>Key differences from monolith:</strong></p>
 * <ul>
 *   <li>Uses Spring Data JPA repositories instead of MyBatis mappers</li>
 *   <li>Keyword search uses JPA JPQL LIKE queries instead of MyBatis dynamic SQL {@code <foreach>}</li>
 *   <li>No L2 cache (MyBatis {@code <cache />}) — caching delegated to JPA/Hibernate second-level cache configuration</li>
 *   <li>All operations are read-only ({@code @Transactional(readOnly = true)})</li>
 * </ul>
 *
 * @see com.jpetstore.catalog.repository.CategoryRepository
 * @see com.jpetstore.catalog.repository.ProductRepository
 * @see com.jpetstore.catalog.repository.ItemRepository
 * @see com.jpetstore.catalog.repository.InventoryRepository
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * Constructs the CatalogService with all required JPA repositories.
     *
     * @param categoryRepository  repository for category CRUD operations
     * @param productRepository   repository for product CRUD and search operations
     * @param itemRepository      repository for item CRUD operations
     * @param inventoryRepository repository for inventory quantity lookups
     */
    public CatalogService(CategoryRepository categoryRepository,
                          ProductRepository productRepository,
                          ItemRepository itemRepository,
                          InventoryRepository inventoryRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.itemRepository = itemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Retrieves all categories from the catalog.
     *
     * <p>Replaces the monolith's {@code categoryMapper.getCategoryList()} which
     * executes: {@code SELECT CATID, NAME, DESCN FROM CATEGORY}.</p>
     *
     * @return a list of all {@link Category} entities; empty list if none exist
     */
    @Transactional(readOnly = true)
    public List<Category> getCategoryList() {
        log.debug("Fetching all categories");
        return categoryRepository.findAll();
    }

    /**
     * Retrieves a single category by its ID.
     *
     * <p>Replaces the monolith's {@code categoryMapper.getCategory(categoryId)} which
     * executes: {@code SELECT CATID, NAME, DESCN FROM CATEGORY WHERE CATID = ?}.</p>
     *
     * @param categoryId the category identifier (e.g., "FISH", "DOGS")
     * @return an {@link Optional} containing the {@link Category} if found;
     *         or {@link Optional#empty()} if no category exists with that ID
     */
    @Transactional(readOnly = true)
    public Optional<Category> getCategory(String categoryId) {
        log.debug("Fetching category by ID: {}", categoryId);
        return categoryRepository.findById(categoryId);
    }

    /**
     * Retrieves a single product by its ID.
     *
     * <p>Replaces the monolith's {@code productMapper.getProduct(productId)} which
     * executes: {@code SELECT PRODUCTID, NAME, DESCN, CATEGORY FROM PRODUCT WHERE PRODUCTID = ?}.</p>
     *
     * @param productId the product identifier (e.g., "FI-SW-01", "K9-BD-01")
     * @return an {@link Optional} containing the {@link Product} if found;
     *         or {@link Optional#empty()} if no product exists with that ID
     */
    @Transactional(readOnly = true)
    public Optional<Product> getProduct(String productId) {
        log.debug("Fetching product by ID: {}", productId);
        return productRepository.findById(productId);
    }

    /**
     * Retrieves all products belonging to a given category.
     *
     * <p>Replaces the monolith's {@code productMapper.getProductListByCategory(categoryId)}
     * which executes: {@code SELECT ... FROM PRODUCT WHERE CATEGORY = ?}.</p>
     *
     * @param categoryId the category identifier to filter products by
     * @return a list of {@link Product} entities in the specified category;
     *         empty list if no products match
     */
    @Transactional(readOnly = true)
    public List<Product> getProductListByCategory(String categoryId) {
        log.debug("Fetching products for category: {}", categoryId);
        return productRepository.findByCategoryCatId(categoryId);
    }

    /**
     * Searches for products whose names match any of the provided keywords.
     *
     * <p>Replicates the monolith's keyword search behavior from
     * {@code CatalogService.searchProductList(String)} (source: CatalogService.java lines 43-52)
     * which tokenizes the input string by whitespace, lowercases each token,
     * wraps with '%' wildcards, and executes a separate LIKE query for each token.
     * Results are aggregated and deduplicated.</p>
     *
     * <p>The monolith's MyBatis implementation uses dynamic SQL with {@code <foreach>}
     * to build an OR chain. This JPA implementation achieves the same result by
     * executing individual queries per keyword and merging results into a
     * deduplicated list.</p>
     *
     * <p><strong>Keyword tokenization rules (matching monolith exactly):</strong></p>
     * <ul>
     *   <li>Input is split by one or more whitespace characters ({@code \\s+})</li>
     *   <li>Each token is lowercased for case-insensitive matching</li>
     *   <li>Each token is wrapped with '%' wildcards: {@code "%" + token + "%"}</li>
     *   <li>Results from all keyword queries are merged and deduplicated by product ID</li>
     * </ul>
     *
     * @param keywords a space-separated string of search keywords
     * @return a deduplicated list of {@link Product} entities whose names match
     *         any of the provided keywords; empty list if keywords is null/blank
     *         or no products match
     */
    @Transactional(readOnly = true)
    public List<Product> searchProductList(String keywords) {
        if (keywords == null || keywords.trim().isEmpty()) {
            log.debug("Empty keyword search — returning empty list");
            return new ArrayList<>();
        }

        // Tokenize by whitespace, lowercase each token, wrap with % wildcards
        // Mirrors monolith: CatalogService.java lines 43-52
        String[] tokens = keywords.trim().split("\\s+");
        Set<String> seenProductIds = new HashSet<>();
        List<Product> results = new ArrayList<>();

        for (String token : tokens) {
            String likePattern = "%" + token.toLowerCase() + "%";
            log.debug("Searching products with keyword pattern: {}", likePattern);
            List<Product> matches = productRepository.searchByName(likePattern);
            for (Product product : matches) {
                // Deduplicate by product ID across all keyword queries
                if (seenProductIds.add(product.getProductId())) {
                    results.add(product);
                }
            }
        }

        log.debug("Product search for '{}' returned {} results", keywords, results.size());
        return results;
    }

    /**
     * Retrieves all items belonging to a given product.
     *
     * <p>Replaces the monolith's {@code itemMapper.getItemListByProduct(productId)}
     * which executes a JOIN query fetching items with their product data.
     * The JPA implementation uses an {@code @EntityGraph} on the repository method
     * to eagerly load the product association, avoiding N+1 queries.</p>
     *
     * @param productId the product identifier to filter items by
     * @return a list of {@link Item} entities for the specified product;
     *         empty list if no items match
     */
    @Transactional(readOnly = true)
    public List<Item> getItemListByProduct(String productId) {
        log.debug("Fetching items for product: {}", productId);
        return itemRepository.findByProductProductId(productId);
    }

    /**
     * Retrieves a single item by its ID, eagerly loading product and supplier
     * associations.
     *
     * <p>Replaces the monolith's {@code itemMapper.getItem(itemId)} which
     * executes a JOIN across item, product, and supplier tables.
     * The JPA implementation uses an {@code @EntityGraph} on the repository method
     * to eagerly load both product and supplier associations in a single query.</p>
     *
     * @param itemId the item identifier (e.g., "EST-1", "EST-14")
     * @return an {@link Optional} containing the {@link Item} with eagerly loaded
     *         product and supplier; or {@link Optional#empty()} if not found
     */
    @Transactional(readOnly = true)
    public Optional<Item> getItem(String itemId) {
        log.debug("Fetching item by ID: {}", itemId);
        return itemRepository.findById(itemId);
    }

    /**
     * Checks whether an item is currently in stock (inventory quantity > 0).
     *
     * <p>Replaces the monolith's {@code itemMapper.getInventoryQuantity(itemId)}
     * return value check. The monolith returns the raw quantity; this method
     * returns a boolean for cleaner API semantics while preserving the same
     * logical check ({@code quantity > 0}).</p>
     *
     * @param itemId the item identifier to check stock for
     * @return {@code true} if the item's inventory quantity is greater than zero;
     *         {@code false} if out of stock or item not found in inventory table
     */
    @Transactional(readOnly = true)
    public boolean isItemInStock(String itemId) {
        log.debug("Checking stock for item: {}", itemId);
        return inventoryRepository.findById(itemId)
                .map(inventory -> inventory.getQty() > 0)
                .orElse(false);
    }

    /**
     * Retrieves the current inventory quantity for a given item.
     *
     * <p>Provides direct access to the inventory quantity value, used by the
     * {@link com.jpetstore.catalog.controller.ItemController} for the
     * {@code GET /api/items/{id}/inventory} endpoint.</p>
     *
     * @param itemId the item identifier to look up inventory for
     * @return the current inventory quantity; or 0 if the item is not found
     *         in the inventory table
     */
    @Transactional(readOnly = true)
    public int getInventoryQuantity(String itemId) {
        log.debug("Fetching inventory quantity for item: {}", itemId);
        return inventoryRepository.findById(itemId)
                .map(Inventory::getQty)
                .orElse(0);
    }
}
