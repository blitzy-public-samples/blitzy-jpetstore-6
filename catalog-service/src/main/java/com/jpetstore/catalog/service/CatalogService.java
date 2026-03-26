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
import java.util.List;

import org.springframework.stereotype.Service;

import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Inventory;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.repository.CategoryRepository;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.ItemRepository;
import com.jpetstore.catalog.repository.ProductRepository;

/**
 * Spring service replicating ALL read operations from the monolith's
 * {@code org.mybatis.jpetstore.service.CatalogService}, converting from
 * MyBatis mapper delegation to Spring Data JPA repository delegation.
 *
 * <p>This class is a direct port of the monolith's CatalogService with
 * <strong>zero business logic changes</strong>. The only modifications are:</p>
 * <ul>
 *   <li>MyBatis mapper interfaces replaced by Spring Data JPA repositories</li>
 *   <li>An additional {@code InventoryRepository} is injected to replace
 *       the monolith's {@code ItemMapper.getInventoryQuantity()} call in
 *       {@link #isItemInStock(String)}</li>
 * </ul>
 *
 * <h3>Monolith Method Mapping</h3>
 * <table>
 *   <caption>1:1 method mapping from monolith CatalogService (lines 47-89)</caption>
 *   <tr><th>Monolith Method</th><th>Mapper Call</th><th>New Repository Call</th></tr>
 *   <tr><td>getCategoryList()</td><td>categoryMapper.getCategoryList()</td>
 *       <td>categoryRepository.findAll()</td></tr>
 *   <tr><td>getCategory(String)</td><td>categoryMapper.getCategory(id)</td>
 *       <td>categoryRepository.findById(id).orElse(null)</td></tr>
 *   <tr><td>getProduct(String)</td><td>productMapper.getProduct(id)</td>
 *       <td>productRepository.findById(id).orElse(null)</td></tr>
 *   <tr><td>getProductListByCategory(String)</td><td>productMapper.getProductListByCategory(id)</td>
 *       <td>productRepository.findByCategoryCatId(id)</td></tr>
 *   <tr><td>searchProductList(String)</td><td>productMapper.searchProductList(pattern)</td>
 *       <td>productRepository.searchByName(pattern)</td></tr>
 *   <tr><td>getItemListByProduct(String)</td><td>itemMapper.getItemListByProduct(id)</td>
 *       <td>itemRepository.findByProductProductId(id)</td></tr>
 *   <tr><td>getItem(String)</td><td>itemMapper.getItem(id)</td>
 *       <td>itemRepository.findById(id).orElse(null)</td></tr>
 *   <tr><td>isItemInStock(String)</td><td>itemMapper.getInventoryQuantity(id) &gt; 0</td>
 *       <td>inventoryRepository.findById(id).map(i -&gt; i.getQty() &gt; 0).orElse(false)</td></tr>
 * </table>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>No {@code @Transactional} annotation — the monolith's CatalogService had none;
 *       all operations are read-only and Spring Data JPA provides default read-only
 *       transaction semantics at the repository level</li>
 *   <li>Methods return JPA entities (not DTOs) — the controller layer handles
 *       entity-to-DTO conversion</li>
 *   <li>Constructor injection follows the exact same pattern as the monolith
 *       (lines 37-45), replacing mappers with repositories</li>
 *   <li>The {@code searchProductList()} keyword tokenization logic is preserved
 *       byte-for-byte from the monolith (lines 71-77): split by {@code \\s+},
 *       lowercase each token, wrap with {@code %}, aggregate via {@code addAll()}</li>
 * </ul>
 *
 * @author Eduardo Macarron (original monolith)
 * @see CategoryRepository
 * @see ProductRepository
 * @see ItemRepository
 * @see InventoryRepository
 */
@Service
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * Constructs the CatalogService with all required JPA repositories.
     *
     * <p>Mirrors the monolith constructor pattern (CatalogService.java lines 41-45)
     * but replaces the three MyBatis mapper parameters with four Spring Data JPA
     * repository parameters. The fourth repository ({@code inventoryRepository})
     * replaces the monolith's {@code itemMapper.getInventoryQuantity()} call.</p>
     *
     * @param categoryRepository  repository for category read operations
     * @param productRepository   repository for product read and search operations
     * @param itemRepository      repository for item read operations with eager loading
     * @param inventoryRepository repository for inventory stock checks
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
     * <p>Replaces monolith line 48: {@code return categoryMapper.getCategoryList();}</p>
     *
     * @return a list of all {@link Category} entities; empty list if none exist
     */
    public List<Category> getCategoryList() {
        return categoryRepository.findAll();
    }

    /**
     * Retrieves a single category by its identifier.
     *
     * <p>Replaces monolith line 52: {@code return categoryMapper.getCategory(categoryId);}</p>
     *
     * <p>Returns {@code null} when no category is found, matching the monolith's
     * nullable return behavior from the MyBatis mapper.</p>
     *
     * @param categoryId the category identifier (e.g., "FISH", "DOGS", "CATS")
     * @return the {@link Category} if found, or {@code null} if not found
     */
    public Category getCategory(String categoryId) {
        return categoryRepository.findById(categoryId).orElse(null);
    }

    /**
     * Retrieves a single product by its identifier.
     *
     * <p>Replaces monolith line 56: {@code return productMapper.getProduct(productId);}</p>
     *
     * <p>Returns {@code null} when no product is found, matching the monolith's
     * nullable return behavior from the MyBatis mapper.</p>
     *
     * @param productId the product identifier (e.g., "FI-SW-01", "K9-BD-01")
     * @return the {@link Product} if found, or {@code null} if not found
     */
    public Product getProduct(String productId) {
        return productRepository.findById(productId).orElse(null);
    }

    /**
     * Retrieves all products belonging to a given category.
     *
     * <p>Replaces monolith line 60:
     * {@code return productMapper.getProductListByCategory(categoryId);}</p>
     *
     * <p>Delegates to {@link ProductRepository#findByCategoryCatId(String)},
     * a derived query that navigates the Product → Category relationship
     * and filters by the Category's {@code catId} field.</p>
     *
     * @param categoryId the category identifier to filter products by
     * @return a list of products in the specified category; empty list if none found
     */
    public List<Product> getProductListByCategory(String categoryId) {
        return productRepository.findByCategoryCatId(categoryId);
    }

    /**
     * Searches for products whose names match any of the provided keywords.
     *
     * <p><strong>CRITICAL: This method replicates the monolith's keyword search
     * logic (CatalogService.java lines 71-77) with ZERO changes.</strong></p>
     *
     * <p>Monolith source (exact):</p>
     * <pre>{@code
     * public List<Product> searchProductList(String keywords) {
     *   List<Product> products = new ArrayList<>();
     *   for (String keyword : keywords.split("\\s+")) {
     *     products.addAll(productMapper.searchProductList("%" + keyword.toLowerCase() + "%"));
     *   }
     *   return products;
     * }
     * }</pre>
     *
     * <p>The ONLY change is the delegation target:
     * {@code productMapper.searchProductList(...)} →
     * {@code productRepository.searchByName(...)}</p>
     *
     * <p>Keyword tokenization rules (preserved exactly):</p>
     * <ul>
     *   <li>Input is split by one or more whitespace characters ({@code \\s+})</li>
     *   <li>Each token is lowercased via {@code toLowerCase()}</li>
     *   <li>Each token is wrapped with SQL wildcards: {@code "%" + token + "%"}</li>
     *   <li>Results from each keyword query are aggregated via {@code addAll()}</li>
     *   <li>No deduplication is performed — matching the monolith behavior</li>
     * </ul>
     *
     * @param keywords a space-separated string of search keywords
     * @return a list of {@link Product} entities matching any keyword; may contain
     *         duplicates if a product matches multiple keywords
     */
    public List<Product> searchProductList(String keywords) {
        List<Product> products = new ArrayList<>();
        for (String keyword : keywords.split("\\s+")) {
            products.addAll(productRepository.searchByName("%" + keyword.toLowerCase() + "%"));
        }
        return products;
    }

    /**
     * Retrieves all items belonging to a given product.
     *
     * <p>Replaces monolith line 80:
     * {@code return itemMapper.getItemListByProduct(productId);}</p>
     *
     * <p>Delegates to {@link ItemRepository#findByProductProductId(String)},
     * a derived query that navigates the Item → Product relationship.
     * The repository method uses {@code @EntityGraph} to eagerly load
     * the Product association, matching the original MyBatis SQL that
     * JOINed ITEM with PRODUCT.</p>
     *
     * @param productId the product identifier to filter items by (e.g., "FI-SW-01")
     * @return a list of items for the specified product; empty list if none found
     */
    public List<Item> getItemListByProduct(String productId) {
        return itemRepository.findByProductProductId(productId);
    }

    /**
     * Retrieves a single item by its identifier with eagerly loaded associations.
     *
     * <p>Replaces monolith line 84: {@code return itemMapper.getItem(itemId);}</p>
     *
     * <p>Returns {@code null} when no item is found, matching the monolith's
     * nullable return behavior from the MyBatis mapper.</p>
     *
     * <p>The {@link ItemRepository#findById(String)} method is overridden with
     * {@code @EntityGraph(attributePaths = {"product", "supplier"})} to eagerly
     * load both the Product and Supplier associations in a single query,
     * matching the original MyBatis SQL that JOINed ITEM, PRODUCT, and INVENTORY.</p>
     *
     * <p><strong>Note:</strong> The monolith's {@code getItem} also JOINed INVENTORY
     * to populate the item's {@code quantity} field. In the decomposed architecture,
     * quantity is managed by the separate {@link Inventory} entity. The controller
     * or service layer must query {@link InventoryRepository} separately if quantity
     * is needed.</p>
     *
     * @param itemId the item identifier (e.g., "EST-1", "EST-14")
     * @return the {@link Item} with eagerly loaded product and supplier if found,
     *         or {@code null} if not found
     */
    public Item getItem(String itemId) {
        return itemRepository.findById(itemId).orElse(null);
    }

    /**
     * Checks whether an item is currently in stock (inventory quantity &gt; 0).
     *
     * <p>Replaces monolith line 88:
     * {@code return itemMapper.getInventoryQuantity(itemId) > 0;}</p>
     *
     * <p>The monolith called {@code ItemMapper.getInventoryQuantity(String)} which
     * executed {@code SELECT QTY AS value FROM INVENTORY WHERE ITEMID = ?} and
     * returned the integer quantity. This method achieves identical behavior by
     * loading the {@link Inventory} entity and checking {@code qty > 0}.</p>
     *
     * <p>If the item is not found in the inventory table, this method returns
     * {@code false}, matching the monolith's behavior where a missing inventory
     * record means the item is not in stock.</p>
     *
     * @param itemId the item identifier to check stock for (e.g., "EST-1")
     * @return {@code true} if the item exists in inventory and has quantity &gt; 0;
     *         {@code false} if out of stock or item not found in inventory table
     */
    public boolean isItemInStock(String itemId) {
        return inventoryRepository.findById(itemId)
                .map(inventory -> inventory.getQty() > 0)
                .orElse(false);
    }

}
