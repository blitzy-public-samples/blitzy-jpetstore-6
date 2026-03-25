/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jpetstore.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.jpetstore.catalog.entity.Inventory;

import jakarta.persistence.EntityManager;

/**
 * Integration test for {@link InventoryRepository}, validating inventory read and
 * atomic decrement operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>This test class is migrated from the monolith's {@code ItemMapperTest.java}
 * (inventory methods only — lines 115-143), adapting the test patterns from
 * MyBatis mapper operations to Spring Data JPA repository operations:
 * <ul>
 *   <li>{@code ItemMapper.getInventoryQuantity(String)} → {@link InventoryRepository#findById(String)}</li>
 *   <li>{@code ItemMapper.updateInventoryQuantity(Map)} → {@link InventoryRepository#decrementQuantity(String, int)}</li>
 * </ul>
 *
 * <p>Additionally, this class introduces three new tests not present in the monolith:
 * <ul>
 *   <li>{@link #testDecrementQuantityInsufficientStock()} — validates the new {@code qty >= :decrement}
 *       safety guard that prevents negative inventory</li>
 *   <li>{@link #testDecrementQuantityExact()} — edge case where decrement equals available quantity</li>
 *   <li>{@link #testFindByIdNotFound()} — verifies empty Optional for nonexistent items</li>
 * </ul>
 *
 * <h3>Key Migration Details</h3>
 * <ul>
 *   <li>Monolith used {@code JdbcTemplate.queryForObject()} to verify DB state after updates;
 *       this test uses {@code EntityManager.flush()/clear()} + {@code findById()} to get fresh data
 *       after {@code @Modifying} queries that bypass the JPA persistence context</li>
 *   <li>The {@code Inventory} entity has a {@code @Version} field for optimistic locking;
 *       test setup does NOT set the version field — JPA manages it automatically</li>
 *   <li>Seed data (EST-1 qty=10000) matches the monolith's {@code jpetstore-hsqldb-dataload.sql}</li>
 * </ul>
 *
 * @see InventoryRepository
 * @see Inventory
 */
@SpringBootTest
@Testcontainers
@Transactional
class InventoryRepositoryIT {

    /**
     * Testcontainers PostgreSQL 16 container — replaces the monolith's embedded HSQLDB
     * used in {@code MapperTestContext.java}. The container is static and shared across
     * all test methods in this class, started once before the first test and stopped
     * after the last test completes.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_catalog_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Wires Testcontainers PostgreSQL connection properties into the Spring Boot
     * application context at runtime. Replaces the monolith's static HSQLDB
     * embedded database configuration from {@code MapperTestContext.dataSource()}.
     *
     * <p>The {@code spring.jpa.hibernate.ddl-auto=create-drop} setting instructs
     * Hibernate to create the schema from JPA entity annotations at startup and
     * drop it on shutdown, validating that all entity mappings are correct.
     *
     * @param registry the dynamic property registry provided by Spring Test
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    /**
     * The Spring Data JPA repository under test. Provides {@code findById(String)}
     * for inventory reads and {@code decrementQuantity(String, int)} for atomic
     * inventory decrements with the {@code qty >= :decrement} safety guard.
     */
    @Autowired
    private InventoryRepository inventoryRepository;

    /**
     * Jakarta Persistence EntityManager used to flush and clear the persistence
     * context after {@code @Modifying @Query} operations. The
     * {@code entityManager.flush()} ensures pending changes are written to the
     * database, and {@code entityManager.clear()} detaches all managed entities
     * so that subsequent {@code findById()} calls fetch fresh data from PostgreSQL
     * rather than returning stale cached entities.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * Seeds the inventory table with test data before each test method.
     *
     * <p>Creates three inventory records matching the monolith's seed data from
     * {@code jpetstore-hsqldb-dataload.sql} (lines 89-91). The {@code @Version} field
     * on the {@link Inventory} entity is NOT set explicitly — JPA manages it automatically,
     * initializing it to {@code 0} on first persist.
     *
     * <p>The repository is cleared first via {@code deleteAll()} to ensure test isolation,
     * even though {@code @Transactional} will roll back changes after each test. This
     * provides defense-in-depth against any accidental transaction commit.
     */
    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        inventoryRepository.flush();

        Inventory est1 = new Inventory();
        est1.setItemId("EST-1");
        est1.setQty(10000);

        Inventory est2 = new Inventory();
        est2.setItemId("EST-2");
        est2.setQty(10000);

        Inventory est3 = new Inventory();
        est3.setItemId("EST-3");
        est3.setQty(10000);

        inventoryRepository.saveAll(List.of(est1, est2, est3));
        inventoryRepository.flush();
    }

    /**
     * Verifies that {@code findById(String)} returns the correct inventory quantity.
     *
     * <p>Migrated from monolith's {@code ItemMapperTest.getInventoryQuantity()} (lines 115-126).
     * The original test called {@code mapper.getInventoryQuantity("EST-1")} returning an
     * {@code int}; this test calls {@code inventoryRepository.findById("EST-1")} returning
     * {@code Optional<Inventory>} and extracts the quantity via {@code getQty()}.
     *
     * <p>Assertion value {@code 10000} matches the monolith's assertion at line 124:
     * {@code assertThat(quantity).isEqualTo(10000)}.
     */
    @Test
    void testFindById() {
        // when
        Optional<Inventory> result = inventoryRepository.findById("EST-1");

        // then
        assertThat(result).isPresent();
        Inventory inventory = result.get();
        assertThat(inventory.getItemId()).isEqualTo("EST-1");
        assertThat(inventory.getQty()).isEqualTo(10000);
    }

    /**
     * Verifies that {@code decrementQuantity(String, int)} successfully decrements
     * inventory when sufficient stock exists.
     *
     * <p>Migrated from monolith's {@code ItemMapperTest.updateInventoryQuantity()} (lines 128-143).
     * The original test decremented by 10 using a {@code Map<String, Object>} parameter and
     * verified the result via {@code JdbcTemplate.queryForObject("SELECT QTY FROM inventory
     * WHERE itemid = ?", Integer.class, itemId)}. This test uses the repository's
     * {@code decrementQuantity()} method and verifies via {@code EntityManager.flush()/clear()}
     * + {@code findById()}.
     *
     * <p>Expected result: quantity changes from 10000 to 9990, matching the monolith's
     * assertion at line 141: {@code assertThat(quantity).isEqualTo(9990)}.
     */
    @Test
    void testDecrementQuantitySuccess() {
        // when — decrement by 10 (sufficient stock: 10000 >= 10)
        int rowsAffected = inventoryRepository.decrementQuantity("EST-1", 10);

        // then — verify 1 row was affected (success)
        assertThat(rowsAffected).isEqualTo(1);

        // Flush pending changes and clear persistence context to force fresh read
        // from PostgreSQL. The @Modifying query bypasses the JPA persistence context,
        // so without flush/clear, findById() would return the stale cached entity.
        entityManager.flush();
        entityManager.clear();

        // Verify the inventory quantity was decremented from 10000 to 9990
        Optional<Inventory> result = inventoryRepository.findById("EST-1");
        assertThat(result).isPresent();
        assertThat(result.get().getQty()).isEqualTo(9990);
    }

    /**
     * Verifies that {@code decrementQuantity(String, int)} returns 0 rows affected
     * when the requested decrement exceeds available stock, and the inventory
     * quantity remains unchanged.
     *
     * <p>This test validates the NEW {@code qty >= :decrement} safety guard in the
     * JPQL WHERE clause that was not present in the monolith's MyBatis SQL:
     * <pre>
     * -- Monolith (no guard): UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}
     * -- New (with guard):    UPDATE Inventory i SET i.qty = i.qty - :decrement
     * --                      WHERE i.itemId = :itemId AND i.qty >= :decrement
     * </pre>
     *
     * <p>Without the guard, this operation would drive inventory negative (5 - 10 = -5).
     * With the guard, the WHERE clause fails to match any rows, returning 0 rows affected.
     */
    @Test
    void testDecrementQuantityInsufficientStock() {
        // given — create inventory with low quantity
        Inventory lowStock = new Inventory();
        lowStock.setItemId("EST-LOW");
        lowStock.setQty(5);
        inventoryRepository.saveAndFlush(lowStock);

        // Clear persistence context so the entity is fully managed from DB
        entityManager.clear();

        // when — attempt to decrement by 10 (more than available 5)
        int rowsAffected = inventoryRepository.decrementQuantity("EST-LOW", 10);

        // then — verify 0 rows affected (insufficient stock)
        assertThat(rowsAffected).isEqualTo(0);

        // Flush and clear persistence context to force fresh read from database
        entityManager.flush();
        entityManager.clear();

        // Verify inventory quantity remains unchanged at 5
        Optional<Inventory> result = inventoryRepository.findById("EST-LOW");
        assertThat(result).isPresent();
        assertThat(result.get().getQty()).isEqualTo(5);
    }

    /**
     * Verifies that {@code decrementQuantity(String, int)} succeeds when the decrement
     * amount exactly equals the available quantity, resulting in zero stock.
     *
     * <p>This is an edge case test for the {@code qty >= :decrement} guard: when
     * {@code qty == decrement}, the condition is satisfied (10 >= 10 is true), so the
     * decrement should proceed successfully, leaving the inventory at exactly 0.
     *
     * <p>This test ensures that the guard uses {@code >=} (greater-than-or-equal)
     * rather than {@code >} (strictly greater-than), which would incorrectly reject
     * exact-quantity decrements.
     */
    @Test
    void testDecrementQuantityExact() {
        // given — create inventory with qty exactly equal to planned decrement
        Inventory exactStock = new Inventory();
        exactStock.setItemId("EST-EXACT");
        exactStock.setQty(10);
        inventoryRepository.saveAndFlush(exactStock);

        // Clear persistence context so the entity is fully managed from DB
        entityManager.clear();

        // when — decrement by exact amount (10 == 10)
        int rowsAffected = inventoryRepository.decrementQuantity("EST-EXACT", 10);

        // then — verify 1 row affected (success: qty >= decrement satisfied when equal)
        assertThat(rowsAffected).isEqualTo(1);

        // Flush and clear persistence context to force fresh read from database
        entityManager.flush();
        entityManager.clear();

        // Verify inventory quantity is now exactly 0
        Optional<Inventory> result = inventoryRepository.findById("EST-EXACT");
        assertThat(result).isPresent();
        assertThat(result.get().getQty()).isEqualTo(0);
    }

    /**
     * Verifies that {@code findById(String)} returns an empty Optional for a
     * nonexistent item identifier.
     *
     * <p>This validates the standard JPA repository behavior and ensures no
     * unexpected default entities are returned for missing inventory records.
     */
    @Test
    void testFindByIdNotFound() {
        // when — look up a nonexistent item
        Optional<Inventory> result = inventoryRepository.findById("NONEXISTENT");

        // then — verify empty Optional
        assertThat(result).isEmpty();
    }
}
