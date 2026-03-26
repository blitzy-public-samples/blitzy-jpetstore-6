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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Inventory;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.repository.CategoryRepository;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.ItemRepository;
import com.jpetstore.catalog.repository.ProductRepository;

/**
 * Unit tests for {@link CatalogService} — the read-only catalog operations
 * service in the Catalog microservice.
 *
 * <p>This test class is a <strong>direct migration</strong> of the monolith's
 * {@code org.mybatis.jpetstore.service.CatalogServiceTest}. The test structure,
 * assertion patterns, and method names are preserved with the following changes:</p>
 * <ul>
 *   <li>Package: {@code org.mybatis.jpetstore.service} →
 *       {@code com.jpetstore.catalog.service}</li>
 *   <li>Mock targets: MyBatis mapper interfaces → Spring Data JPA repositories</li>
 *   <li>Domain classes → JPA entity classes (same field structure)</li>
 *   <li>Mapper method calls → Repository method calls (some returning {@code Optional})</li>
 *   <li>Added {@code InventoryRepository} mock for {@code isItemInStock} which was
 *       on ItemMapper in the monolith</li>
 * </ul>
 *
 * <p><strong>CRITICAL</strong>: Zero business logic changes. All 9 test methods
 * test the SAME service methods with IDENTICAL behavior as the monolith tests.</p>
 *
 * @author Eduardo Macarron (original monolith)
 * @see CatalogService
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock(lenient = true)
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private CatalogService catalogService;

    /**
     * Verifies the keyword tokenization logic in {@link CatalogService#searchProductList(String)}.
     *
     * <p>Migrated from monolith's {@code shouldCallTheSearchMapperTwice} (lines 52-70).
     * The keywords "a b" are split into two tokens ("a" and "b"), each lowercased and
     * wrapped with SQL wildcards ("%a%" and "%b%"). The service aggregates results from
     * both repository calls via {@code addAll()}.</p>
     *
     * <p>Change: {@code productMapper.searchProductList()} →
     * {@code productRepository.searchByName()}</p>
     */
    @Test
    void shouldCallTheSearchRepositoryTwice() {
        // given
        String keywords = "a b";
        List<Product> l1 = new ArrayList<>();
        l1.add(new Product());
        List<Product> l2 = new ArrayList<>();
        l2.add(new Product());

        // when
        when(productRepository.searchByName("%a%")).thenReturn(l1);
        when(productRepository.searchByName("%b%")).thenReturn(l2);
        List<Product> r = catalogService.searchProductList(keywords);

        // then
        assertThat(r).hasSize(2);
        assertThat(r.get(0)).isSameAs(l1.get(0));
        assertThat(r.get(1)).isSameAs(l2.get(0));
    }

    /**
     * Verifies {@link CatalogService#getCategoryList()} delegates to
     * {@link CategoryRepository#findAll()} and returns the same list instance.
     *
     * <p>Migrated from monolith lines 72-83.
     * Change: {@code categoryMapper.getCategoryList()} →
     * {@code categoryRepository.findAll()}</p>
     */
    @Test
    void shouldReturnCategoryList() {
        // given
        List<Category> expectedCategories = new ArrayList<>();

        // when
        when(categoryRepository.findAll()).thenReturn(expectedCategories);
        List<Category> categories = catalogService.getCategoryList();

        // then
        assertThat(categories).isSameAs(expectedCategories);
    }

    /**
     * Verifies {@link CatalogService#getCategory(String)} delegates to
     * {@link CategoryRepository#findById(String)} and unwraps the Optional.
     *
     * <p>Migrated from monolith lines 85-98.
     * Change: {@code categoryMapper.getCategory(categoryId)} returning Category →
     * {@code categoryRepository.findById(categoryId)} returning Optional&lt;Category&gt;</p>
     */
    @Test
    void shouldReturnCategory() {

        // given
        String categoryId = "C01";
        Category expectedCategory = new Category();

        // when
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(expectedCategory));
        Category category = catalogService.getCategory(categoryId);

        // then
        assertThat(category).isSameAs(expectedCategory);

    }

    /**
     * Verifies {@link CatalogService#getProduct(String)} delegates to
     * {@link ProductRepository#findById(String)} and unwraps the Optional.
     *
     * <p>Migrated from monolith lines 100-114.
     * Change: {@code productMapper.getProduct(productId)} returning Product →
     * {@code productRepository.findById(productId)} returning Optional&lt;Product&gt;</p>
     */
    @Test
    void shouldReturnProduct() {

        // given
        String productId = "P01";
        Product expectedProduct = new Product();

        // when
        when(productRepository.findById(productId)).thenReturn(Optional.of(expectedProduct));
        Product product = catalogService.getProduct(productId);

        // then
        assertThat(product).isSameAs(expectedProduct);

    }

    /**
     * Verifies {@link CatalogService#getProductListByCategory(String)} delegates to
     * {@link ProductRepository#findByCategoryCatId(String)}.
     *
     * <p>Migrated from monolith lines 116-130.
     * Change: {@code productMapper.getProductListByCategory(categoryId)} →
     * {@code productRepository.findByCategoryCatId(categoryId)}</p>
     */
    @Test
    void shouldReturnProductList() {
        // given
        String categoryId = "C01";
        List<Product> expectedProducts = new ArrayList<>();

        // when
        when(productRepository.findByCategoryCatId(categoryId)).thenReturn(expectedProducts);
        List<Product> products = catalogService.getProductListByCategory(categoryId);

        // then
        assertThat(products).isSameAs(expectedProducts);

    }

    /**
     * Verifies {@link CatalogService#getItemListByProduct(String)} delegates to
     * {@link ItemRepository#findByProductProductId(String)}.
     *
     * <p>Migrated from monolith lines 132-145.
     * Change: {@code itemMapper.getItemListByProduct(productId)} →
     * {@code itemRepository.findByProductProductId(productId)}</p>
     */
    @Test
    void shouldReturnItemList() {
        // given
        String productId = "P01";
        List<Item> expectedItems = new ArrayList<>();

        // when
        when(itemRepository.findByProductProductId(productId)).thenReturn(expectedItems);
        List<Item> items = catalogService.getItemListByProduct(productId);

        // then
        assertThat(items).isSameAs(expectedItems);

    }

    /**
     * Verifies {@link CatalogService#getItem(String)} delegates to
     * {@link ItemRepository#findById(String)} and unwraps the Optional.
     *
     * <p>Migrated from monolith lines 147-161.
     * Change: {@code itemMapper.getItem(itemCode)} returning Item →
     * {@code itemRepository.findById(itemCode)} returning Optional&lt;Item&gt;</p>
     */
    @Test
    void shouldReturnItem() {

        // given
        String itemCode = "I01";
        Item expectedItem = new Item();

        // when
        when(itemRepository.findById(itemCode)).thenReturn(Optional.of(expectedItem));
        Item item = catalogService.getItem(itemCode);

        // then
        assertThat(item).isSameAs(expectedItem);

    }

    /**
     * Verifies that {@link CatalogService#isItemInStock(String)} returns {@code true}
     * when the inventory quantity is greater than zero.
     *
     * <p>Migrated from monolith lines 163-176.
     * Change: {@code itemMapper.getInventoryQuantity(itemCode)} returning int →
     * {@code inventoryRepository.findById(itemCode)} returning Optional&lt;Inventory&gt;.
     * The CatalogService internally does
     * {@code .map(inventory -> inventory.getQty() > 0).orElse(false)}.</p>
     */
    @Test
    void shouldReturnTrueWhenExistStock() {

        // given
        String itemCode = "I01";
        Inventory inventory = new Inventory();
        inventory.setItemId(itemCode);
        inventory.setQty(1);

        // when
        when(inventoryRepository.findById(itemCode)).thenReturn(Optional.of(inventory));
        boolean result = catalogService.isItemInStock(itemCode);

        // then
        assertThat(result).isTrue();

    }

    /**
     * Verifies that {@link CatalogService#isItemInStock(String)} returns {@code false}
     * when the inventory quantity is zero.
     *
     * <p>Migrated from monolith lines 178-191.
     * Change: {@code itemMapper.getInventoryQuantity(itemCode)} returning 0 →
     * {@code inventoryRepository.findById(itemCode)} returning Optional with qty=0.
     * The CatalogService evaluates {@code 0 > 0} = false via the lambda.</p>
     */
    @Test
    void shouldReturnFalseWhenNotExistStock() {

        // given
        String itemCode = "I01";
        Inventory inventory = new Inventory();
        inventory.setItemId(itemCode);
        inventory.setQty(0);

        // when
        when(inventoryRepository.findById(itemCode)).thenReturn(Optional.of(inventory));
        boolean result = catalogService.isItemInStock(itemCode);

        // then
        assertThat(result).isFalse();

    }

}
