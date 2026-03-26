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

import com.jpetstore.catalog.entity.Supplier;

/**
 * Integration test for {@link SupplierRepository} verifying CRUD operations
 * against a real PostgreSQL database via Testcontainers.
 *
 * <p>This is a new integration test with no monolith counterpart — the supplier
 * table was only referenced as a foreign key in the original JPetStore monolith.
 * In the decomposed microservices architecture, Supplier is promoted to a
 * first-class entity with its own repository and dedicated integration tests.</p>
 *
 * <p>Tests cover the inherited {@link org.springframework.data.jpa.repository.JpaRepository}
 * methods: {@code findById(Integer)}, {@code findAll()}, and {@code save(Supplier)}.</p>
 *
 * <h3>Test Data</h3>
 * <p>Seed data matches the original monolith's {@code jpetstore-hsqldb-dataload.sql}
 * supplier records:</p>
 * <ul>
 *   <li>Supplier 1: XYZ Pets, Los Angeles, CA</li>
 *   <li>Supplier 2: ABC Pets, San Francisco, CA</li>
 * </ul>
 *
 * @see SupplierRepository
 * @see Supplier
 */
@SpringBootTest
@Testcontainers
@Transactional
class SupplierRepositoryIT {

    // ──────────────────────────────────────────────────────────────────────────
    // Testcontainers PostgreSQL Setup
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * PostgreSQL 16 container shared across all test methods.
     * Replaces the monolith's embedded HSQLDB (MapperTestContext.java) with
     * a real PostgreSQL instance for accurate integration testing.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_catalog_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Wires Testcontainers PostgreSQL connection properties into the Spring Boot
     * application context at runtime before bean creation.
     *
     * <ul>
     *   <li>{@code spring.datasource.url} — dynamic JDBC URL from container</li>
     *   <li>{@code spring.datasource.username} — container username</li>
     *   <li>{@code spring.datasource.password} — container password</li>
     *   <li>{@code spring.jpa.hibernate.ddl-auto=create-drop} — Hibernate creates
     *       schema from entity annotations and drops on shutdown</li>
     *   <li>{@code spring.liquibase.enabled=false} — disable Liquibase for tests
     *       since Hibernate manages schema via create-drop</li>
     * </ul>
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
    // Injected Dependencies
    // ──────────────────────────────────────────────────────────────────────────

    @Autowired
    private SupplierRepository supplierRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // Test Data Setup
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Seeds two supplier entities before each test method, matching the original
     * monolith's {@code jpetstore-hsqldb-dataload.sql} supplier records.
     *
     * <p>The repository is cleared first to ensure test isolation. Combined with
     * the class-level {@code @Transactional} annotation (which rolls back after
     * each test), this guarantees a clean state for every test method.</p>
     */
    @BeforeEach
    void setUp() {
        supplierRepository.deleteAll();
        supplierRepository.flush();

        // Supplier 1: XYZ Pets — matches dataload SQL line 57
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

        // Supplier 2: ABC Pets — matches dataload SQL line 58
        Supplier supplier2 = new Supplier();
        supplier2.setSuppId(2);
        supplier2.setName("ABC Pets");
        supplier2.setStatus("AC");
        supplier2.setAddr1("700 Abalone Way");
        supplier2.setAddr2("");
        supplier2.setCity("San Francisco");
        supplier2.setState("CA");
        supplier2.setZip("94024");
        supplier2.setPhone("415-947-0797");

        supplierRepository.saveAll(List.of(supplier1, supplier2));
        supplierRepository.flush();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code findById(Integer)} correctly retrieves a supplier
     * by its primary key and that all 9 fields are populated accurately.
     *
     * <p>Given: Two suppliers are seeded in {@code @BeforeEach}<br>
     * When: {@code findById(1)} is called<br>
     * Then: The returned Optional contains a Supplier with all 9 fields
     * matching the XYZ Pets seed data.</p>
     */
    @Test
    void testFindById() {
        // When
        Optional<Supplier> result = supplierRepository.findById(1);

        // Then
        assertThat(result).isPresent();

        Supplier supplier = result.orElseThrow();
        assertThat(supplier.getSuppId()).isEqualTo(1);
        assertThat(supplier.getName()).isEqualTo("XYZ Pets");
        assertThat(supplier.getStatus()).isEqualTo("AC");
        assertThat(supplier.getAddr1()).isEqualTo("600 Avon Way");
        assertThat(supplier.getAddr2()).isEqualTo("");
        assertThat(supplier.getCity()).isEqualTo("Los Angeles");
        assertThat(supplier.getState()).isEqualTo("CA");
        assertThat(supplier.getZip()).isEqualTo("94024");
        assertThat(supplier.getPhone()).isEqualTo("212-947-0797");
    }

    /**
     * Verifies that {@code findAll()} retrieves all seeded suppliers and that
     * the results are correctly populated.
     *
     * <p>Given: Two suppliers are seeded in {@code @BeforeEach}<br>
     * When: {@code findAll()} is called<br>
     * Then: The returned list contains exactly 2 suppliers, sorted by suppId,
     * with correct names matching the seed data.</p>
     */
    @Test
    void testFindAll() {
        // When
        List<Supplier> suppliers = supplierRepository.findAll();

        // Then
        assertThat(suppliers).hasSize(2);

        // Sort by suppId for deterministic assertion ordering
        suppliers.sort(Comparator.comparingInt(Supplier::getSuppId));

        // Verify first supplier: XYZ Pets (suppId=1)
        assertThat(suppliers.get(0).getSuppId()).isEqualTo(1);
        assertThat(suppliers.get(0).getName()).isEqualTo("XYZ Pets");
        assertThat(suppliers.get(0).getStatus()).isEqualTo("AC");
        assertThat(suppliers.get(0).getCity()).isEqualTo("Los Angeles");

        // Verify second supplier: ABC Pets (suppId=2)
        assertThat(suppliers.get(1).getSuppId()).isEqualTo(2);
        assertThat(suppliers.get(1).getName()).isEqualTo("ABC Pets");
        assertThat(suppliers.get(1).getStatus()).isEqualTo("AC");
        assertThat(suppliers.get(1).getCity()).isEqualTo("San Francisco");
    }

    /**
     * Verifies that {@code save(Supplier)} correctly persists a new supplier
     * entity and that it can be retrieved via {@code findById(Integer)}.
     *
     * <p>Given: Two suppliers are seeded in {@code @BeforeEach}<br>
     * When: A new third supplier is saved<br>
     * Then: The new supplier is retrievable by its ID with all fields intact,
     * and {@code findAll()} returns 3 suppliers.</p>
     */
    @Test
    void testSave() {
        // Given — create a new supplier
        Supplier newSupplier = new Supplier();
        newSupplier.setSuppId(3);
        newSupplier.setName("New Pets");
        newSupplier.setStatus("AC");
        newSupplier.setAddr1("800 Broadway");
        newSupplier.setAddr2("Suite 100");
        newSupplier.setCity("New York");
        newSupplier.setState("NY");
        newSupplier.setZip("10001");
        newSupplier.setPhone("212-555-1234");

        // When
        supplierRepository.save(newSupplier);
        supplierRepository.flush();

        // Then — verify the saved supplier can be retrieved
        Optional<Supplier> result = supplierRepository.findById(3);
        assertThat(result).isPresent();

        Supplier saved = result.orElseThrow();
        assertThat(saved.getSuppId()).isEqualTo(3);
        assertThat(saved.getName()).isEqualTo("New Pets");
        assertThat(saved.getStatus()).isEqualTo("AC");
        assertThat(saved.getAddr1()).isEqualTo("800 Broadway");
        assertThat(saved.getAddr2()).isEqualTo("Suite 100");
        assertThat(saved.getCity()).isEqualTo("New York");
        assertThat(saved.getState()).isEqualTo("NY");
        assertThat(saved.getZip()).isEqualTo("10001");
        assertThat(saved.getPhone()).isEqualTo("212-555-1234");

        // Verify findAll now returns 3 suppliers
        List<Supplier> allSuppliers = supplierRepository.findAll();
        assertThat(allSuppliers).hasSize(3);
    }

    /**
     * Verifies that {@code findById(Integer)} returns an empty Optional when
     * querying for a non-existent supplier ID.
     *
     * <p>Given: Two suppliers are seeded in {@code @BeforeEach}<br>
     * When: {@code findById(999)} is called for a non-existent ID<br>
     * Then: The returned Optional is empty.</p>
     */
    @Test
    void testFindByIdNotFound() {
        // When
        Optional<Supplier> result = supplierRepository.findById(999);

        // Then
        assertThat(result).isEmpty();
    }
}
