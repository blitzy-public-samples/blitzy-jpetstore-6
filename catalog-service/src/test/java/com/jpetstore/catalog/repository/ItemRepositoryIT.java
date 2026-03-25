/*
 * Copyright 2010-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jpetstore.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.entity.Supplier;

/**
 * Integration test for {@link ItemRepository} — verifies Spring Data JPA
 * derived query methods and {@code @EntityGraph}-annotated finders against
 * a real PostgreSQL 16 database managed by Testcontainers.
 *
 * <p>Migrated from the monolith's {@code ItemMapperTest.java}. Only the
 * <strong>item-read</strong> methods are tested here:
 * <ul>
 *   <li>{@code findByProductProductId(String)} — replaces
 *       {@code ItemMapper.getItemListByProduct(String)}</li>
 *   <li>{@code findById(String)} — replaces
 *       {@code ItemMapper.getItem(String)} with {@code @EntityGraph}
 *       for eager Product + Supplier loading</li>
 * </ul>
 *
 * <p>The inventory-related methods ({@code getInventoryQuantity},
 * {@code updateInventoryQuantity}) are <strong>NOT</strong> tested here —
 * they belong to {@code InventoryRepositoryIT} which manages the separate
 * {@code Inventory} entity with {@code @Version} for optimistic locking.</p>
 *
 * <h3>Key Migration Changes from Monolith ItemMapperTest</h3>
 * <table>
 *   <tr><th>Monolith Access</th><th>JPA Entity Access</th></tr>
 *   <tr>
 *     <td>{@code item.getSupplierId()} (returns int)</td>
 *     <td>{@code item.getSupplier().getSuppId()} (navigates @ManyToOne)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code item.getProduct().getCategoryId()} (returns String)</td>
 *     <td>{@code item.getProduct().getCategory().getCatId()} (navigates @ManyToOne chain)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code item.getQuantity()} (returns int)</td>
 *     <td>NOT AVAILABLE — quantity is in Inventory entity</td>
 *   </tr>
 * </table>
 *
 * @see ItemRepository
 * @see Item
 * @see Product
 * @see Category
 * @see Supplier
 */
@SpringBootTest
@Testcontainers
@Transactional
class ItemRepositoryIT {

    // ──────────────────────────────────────────────────────────────────────────
    // Testcontainers PostgreSQL Setup
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Shared PostgreSQL 16 container for all test methods. Managed by the
     * Testcontainers JUnit 5 extension — started before the first test,
     * stopped after the last test completes.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_catalog_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Injects Testcontainers PostgreSQL connection properties into the
     * Spring Boot application context at runtime, replacing any static
     * datasource configuration from {@code application.yml}.
     *
     * <p>{@code spring.jpa.hibernate.ddl-auto=create-drop} causes Hibernate
     * to generate the database schema from JPA entity annotations at startup
     * and drop it on shutdown, which is appropriate for isolated integration
     * tests.</p>
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Injected Repositories
    // ──────────────────────────────────────────────────────────────────────────

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // Test Data Setup
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Seeds test data before each test method, respecting the FK hierarchy:
     * Supplier (independent), Category (independent) → Product (depends on
     * Category) → Item (depends on Product and Supplier).
     *
     * <p>Data matches the monolith's {@code jpetstore-hsqldb-dataload.sql}
     * seed data for FISH category, Angelfish product, and EST-1/EST-2 items
     * used in the original {@code ItemMapperTest}.</p>
     *
     * <p>Deletion order is reverse FK: Items first, then Products, then
     * Categories and Suppliers — preventing FK constraint violations.</p>
     */
    @BeforeEach
    void setUp() {
        // Clear existing data in reverse FK order to avoid constraint violations
        itemRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        supplierRepository.deleteAll();

        // --- Supplier (independent entity — no FK dependencies) ---
        Supplier supplier1 = new Supplier();
        supplier1.setSuppId(1);
        supplier1.setName("XYZ Pets");
        supplier1.setStatus("AC");
        supplier1.setAddr1("600 Avon Way");
        supplier1.setAddr2("");
        supplier1.setCity("Los Angeles");
        supplier1.setState("CA");
        supplier1.setZip("94024");
        supplier1.setPhone("212-947-0797");
        supplierRepository.save(supplier1);

        // --- Category (independent entity — no FK dependencies) ---
        Category fishCategory = new Category();
        fishCategory.setCatId("FISH");
        fishCategory.setName("Fish");
        fishCategory.setDescription("<image src=\"../images/fish_icon.gif\">"
                + "<font size=\"5\" color=\"blue\"> Fish</font>");
        categoryRepository.save(fishCategory);

        // --- Product (depends on Category via @ManyToOne FK) ---
        Product angelfish = new Product();
        angelfish.setProductId("FI-SW-01");
        angelfish.setCategory(fishCategory);
        angelfish.setName("Angelfish");
        angelfish.setDescription("<image src=\"../images/fish1.gif\">"
                + "Salt Water fish from Australia");
        productRepository.save(angelfish);

        // --- Items (depend on Product and Supplier via @ManyToOne FKs) ---

        // EST-1: Large Angelfish (matches monolith seed data)
        Item item1 = new Item();
        item1.setItemId("EST-1");
        item1.setProduct(angelfish);
        item1.setListPrice(new BigDecimal("16.50"));
        item1.setUnitCost(new BigDecimal("10.00"));
        item1.setSupplier(supplier1);
        item1.setStatus("P");
        item1.setAttribute1("Large");
        // attribute2 through attribute5 intentionally left null (matches seed data)

        // EST-2: Small Angelfish (matches monolith seed data)
        Item item2 = new Item();
        item2.setItemId("EST-2");
        item2.setProduct(angelfish);
        item2.setListPrice(new BigDecimal("16.50"));
        item2.setUnitCost(new BigDecimal("10.00"));
        item2.setSupplier(supplier1);
        item2.setStatus("P");
        item2.setAttribute1("Small");
        // attribute2 through attribute5 intentionally left null (matches seed data)

        itemRepository.saveAll(List.of(item1, item2));
        itemRepository.flush();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link ItemRepository#findByProductProductId(String)}
     * returns all items belonging to a given product, with the Product
     * association eagerly loaded via {@code @EntityGraph}.
     *
     * <p>Migrated from monolith's {@code ItemMapperTest.getItemListByProduct()}
     * (lines 47–87). All original assertions are preserved with the following
     * adaptations for the JPA entity model:</p>
     * <ul>
     *   <li>{@code getSupplierId()} → {@code getSupplier().getSuppId()}</li>
     *   <li>{@code getProduct().getCategoryId()} →
     *       {@code getProduct().getCategory().getCatId()}</li>
     * </ul>
     */
    @Test
    void testFindByProductProductId() {
        // when
        List<Item> items = itemRepository.findByProductProductId("FI-SW-01");

        // then — sort by itemId to ensure deterministic assertion order
        items.sort(Comparator.comparing(Item::getItemId));
        assertThat(items).hasSize(2);

        // --- First item: EST-1 (Large Angelfish) ---
        assertThat(items.get(0).getItemId()).isEqualTo("EST-1");
        assertThat(items.get(0).getListPrice()).isEqualTo(new BigDecimal("16.50"));
        assertThat(items.get(0).getUnitCost()).isEqualTo(new BigDecimal("10.00"));
        assertThat(items.get(0).getSupplier().getSuppId()).isEqualTo(1);
        assertThat(items.get(0).getStatus()).isEqualTo("P");
        assertThat(items.get(0).getAttribute1()).isEqualTo("Large");
        assertThat(items.get(0).getAttribute2()).isNull();
        assertThat(items.get(0).getAttribute3()).isNull();
        assertThat(items.get(0).getAttribute4()).isNull();
        assertThat(items.get(0).getAttribute5()).isNull();
        assertThat(items.get(0).getProduct().getProductId()).isEqualTo("FI-SW-01");
        assertThat(items.get(0).getProduct().getName()).isEqualTo("Angelfish");
        assertThat(items.get(0).getProduct().getDescription())
                .isEqualTo("<image src=\"../images/fish1.gif\">Salt Water fish from Australia");
        assertThat(items.get(0).getProduct().getCategory().getCatId()).isEqualTo("FISH");

        // --- Second item: EST-2 (Small Angelfish) ---
        assertThat(items.get(1).getItemId()).isEqualTo("EST-2");
        assertThat(items.get(1).getListPrice()).isEqualTo(new BigDecimal("16.50"));
        assertThat(items.get(1).getUnitCost()).isEqualTo(new BigDecimal("10.00"));
        assertThat(items.get(1).getSupplier().getSuppId()).isEqualTo(1);
        assertThat(items.get(1).getStatus()).isEqualTo("P");
        assertThat(items.get(1).getAttribute1()).isEqualTo("Small");
        assertThat(items.get(1).getAttribute2()).isNull();
        assertThat(items.get(1).getAttribute3()).isNull();
        assertThat(items.get(1).getAttribute4()).isNull();
        assertThat(items.get(1).getAttribute5()).isNull();
        assertThat(items.get(1).getProduct().getProductId()).isEqualTo("FI-SW-01");
        assertThat(items.get(1).getProduct().getName()).isEqualTo("Angelfish");
        assertThat(items.get(1).getProduct().getDescription())
                .isEqualTo("<image src=\"../images/fish1.gif\">Salt Water fish from Australia");
        assertThat(items.get(1).getProduct().getCategory().getCatId()).isEqualTo("FISH");
    }

    /**
     * Verifies that {@link ItemRepository#findById(String)} returns an item
     * with both Product and Supplier eagerly loaded via
     * {@code @EntityGraph(attributePaths = {"product", "supplier"})}.
     *
     * <p>Migrated from monolith's {@code ItemMapperTest.getItem()} (lines
     * 89–113). All original assertions are preserved with the same JPA entity
     * model adaptations as {@link #testFindByProductProductId()}.</p>
     *
     * <p><strong>Note:</strong> The monolith's {@code getItem()} SQL also
     * JOINed the INVENTORY table and populated {@code item.quantity}. In the
     * decomposed architecture, inventory is a separate entity — this test
     * does NOT verify quantity. That is the responsibility of
     * {@code InventoryRepositoryIT}.</p>
     */
    @Test
    void testFindById() {
        // when — uses @EntityGraph for eager Product + Supplier loading
        Optional<Item> result = itemRepository.findById("EST-1");

        // then
        assertThat(result).isPresent();
        Item item = result.orElseThrow();

        assertThat(item.getItemId()).isEqualTo("EST-1");
        assertThat(item.getListPrice()).isEqualTo(new BigDecimal("16.50"));
        assertThat(item.getUnitCost()).isEqualTo(new BigDecimal("10.00"));
        assertThat(item.getSupplier().getSuppId()).isEqualTo(1);
        assertThat(item.getStatus()).isEqualTo("P");
        assertThat(item.getAttribute1()).isEqualTo("Large");
        assertThat(item.getAttribute2()).isNull();
        assertThat(item.getAttribute3()).isNull();
        assertThat(item.getAttribute4()).isNull();
        assertThat(item.getAttribute5()).isNull();
        assertThat(item.getProduct().getProductId()).isEqualTo("FI-SW-01");
        assertThat(item.getProduct().getName()).isEqualTo("Angelfish");
        assertThat(item.getProduct().getDescription())
                .isEqualTo("<image src=\"../images/fish1.gif\">Salt Water fish from Australia");
        assertThat(item.getProduct().getCategory().getCatId()).isEqualTo("FISH");
    }

    /**
     * Verifies that {@link ItemRepository#findById(String)} returns an empty
     * {@link Optional} when the requested item ID does not exist in the
     * database.
     *
     * <p>This is a new test (not present in the monolith's
     * {@code ItemMapperTest}) that validates the JPA repository's standard
     * behavior for missing entities.</p>
     */
    @Test
    void testFindByIdNotFound() {
        // when
        Optional<Item> result = itemRepository.findById("NONEXISTENT");

        // then
        assertThat(result).isEmpty();
    }

}
