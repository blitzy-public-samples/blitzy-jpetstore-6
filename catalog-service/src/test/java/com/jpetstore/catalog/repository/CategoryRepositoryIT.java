/*
 * JPetStore Catalog Service — CategoryRepository Integration Test
 *
 * Migrated from the monolith's org.mybatis.jpetstore.mapper.CategoryMapperTest.
 * Tests CategoryRepository's findAll() and findById() methods against seeded
 * category data (5 categories: BIRDS, CATS, DOGS, FISH, REPTILES) using
 * Testcontainers PostgreSQL 16 instead of the monolith's embedded HSQLDB.
 *
 * Migration mapping:
 *   CategoryMapper.getCategoryList()       → CategoryRepository.findAll()
 *   CategoryMapper.getCategory(String)     → CategoryRepository.findById(String) → Optional<Category>
 *
 * Key differences from monolith test:
 *   - Uses Testcontainers PostgreSQL 16 instead of HSQLDB (MapperTestContext.java)
 *   - Uses Spring Data JPA (JpaRepository) instead of MyBatis mapper
 *   - Entity field name: catId (JPA) instead of categoryId (MyBatis alias)
 *   - findById returns Optional<Category> instead of nullable Category
 *   - @SpringBootTest loads full context instead of @ContextConfiguration(MapperTestContext.class)
 *   - @DynamicPropertySource wires Testcontainers instead of embedded DB builder
 *   - Added testFindByIdNotFound() (not in monolith test)
 *
 * Test data:
 *   Seeded from jpetstore-hsqldb-dataload.sql lines 34-38 (5 category INSERTs)
 *   with HTML descriptions preserved exactly as in the monolith.
 *
 * @see com.jpetstore.catalog.repository.CategoryRepository
 * @see com.jpetstore.catalog.entity.Category
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

import com.jpetstore.catalog.entity.Category;

/**
 * Integration test for {@link CategoryRepository} verifying Spring Data JPA
 * repository operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>This test class replaces the monolith's {@code CategoryMapperTest} which
 * tested the MyBatis {@code CategoryMapper} against an embedded HSQLDB instance
 * configured via {@code MapperTestContext}. The migration preserves all original
 * test assertions while adapting to the new JPA-based persistence layer.</p>
 *
 * <p>The {@code @Transactional} annotation ensures each test method executes
 * within a transaction that is rolled back after completion, providing test
 * isolation identical to the monolith's {@code CategoryMapperTest} pattern.</p>
 *
 * <p>Test data is seeded in {@link #setUp()} from the exact same values as the
 * monolith's {@code jpetstore-hsqldb-dataload.sql} (lines 34-38), ensuring
 * behavioral parity between the original MyBatis mapper and the new JPA
 * repository.</p>
 */
@SpringBootTest
@Testcontainers
@Transactional
class CategoryRepositoryIT {

    /**
     * Testcontainers-managed PostgreSQL 16 instance replacing the monolith's
     * embedded HSQLDB configured in {@code MapperTestContext.dataSource()}.
     * The container is automatically started before any test method and stopped
     * after all tests complete.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /**
     * Wires the Testcontainers PostgreSQL connection properties into the Spring
     * Boot application context, replacing the monolith's embedded HSQLDB
     * datasource. Also sets Hibernate DDL-auto to {@code create-drop} so that
     * JPA entities define the schema (Liquibase is disabled for test isolation).
     *
     * @param registry Spring dynamic property registry for runtime property injection
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
     * Spring Data JPA repository under test — replaces the monolith's
     * {@code CategoryMapper} MyBatis mapper interface.
     */
    @Autowired
    private CategoryRepository categoryRepository;

    /**
     * Seeds the test database with 5 categories before each test method.
     * Data matches exactly the monolith's {@code jpetstore-hsqldb-dataload.sql}
     * lines 34-38. The repository is cleared first to ensure a clean state.
     *
     * <p>Category descriptions contain HTML markup with {@code <image>} and
     * {@code <font>} tags that must be preserved exactly as in the monolith.</p>
     */
    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();

        Category fish = new Category();
        fish.setCatId("FISH");
        fish.setName("Fish");
        fish.setDescription("<image src=\"../images/fish_icon.gif\"><font size=\"5\" color=\"blue\"> Fish</font>");

        Category dogs = new Category();
        dogs.setCatId("DOGS");
        dogs.setName("Dogs");
        dogs.setDescription("<image src=\"../images/dogs_icon.gif\"><font size=\"5\" color=\"blue\"> Dogs</font>");

        Category reptiles = new Category();
        reptiles.setCatId("REPTILES");
        reptiles.setName("Reptiles");
        reptiles.setDescription("<image src=\"../images/reptiles_icon.gif\"><font size=\"5\" color=\"blue\"> Reptiles</font>");

        Category cats = new Category();
        cats.setCatId("CATS");
        cats.setName("Cats");
        cats.setDescription("<image src=\"../images/cats_icon.gif\"><font size=\"5\" color=\"blue\"> Cats</font>");

        Category birds = new Category();
        birds.setCatId("BIRDS");
        birds.setName("Birds");
        birds.setDescription("<image src=\"../images/birds_icon.gif\"><font size=\"5\" color=\"blue\"> Birds</font>");

        categoryRepository.saveAll(List.of(fish, dogs, reptiles, cats, birds));
        categoryRepository.flush();
    }

    /**
     * Tests {@link CategoryRepository#findAll()} — replaces the monolith's
     * {@code CategoryMapperTest.getCategoryList()} (lines 40-69).
     *
     * <p>Verifies that all 5 seeded categories are returned and that each
     * category's catId, name, and description match the expected seed data
     * exactly. Results are sorted by catId for deterministic ordering,
     * matching the monolith's assertion pattern at line 47.</p>
     *
     * <p>Key migration detail: uses {@code getCatId()} instead of the
     * monolith's {@code getCategoryId()} due to the JPA entity field rename.</p>
     */
    @Test
    void testFindAll() {
        // when
        List<Category> categories = categoryRepository.findAll();

        // then — sort by catId for deterministic ordering
        // (mirrors monolith's Comparator.comparing(Category::getCategoryId) at line 47,
        //  but uses getCatId() per JPA entity field name)
        categories.sort(Comparator.comparing(Category::getCatId));

        assertThat(categories).hasSize(5);

        // Index 0: BIRDS (alphabetical by catId)
        assertThat(categories.get(0).getCatId()).isEqualTo("BIRDS");
        assertThat(categories.get(0).getName()).isEqualTo("Birds");
        assertThat(categories.get(0).getDescription())
                .isEqualTo("<image src=\"../images/birds_icon.gif\"><font size=\"5\" color=\"blue\"> Birds</font>");

        // Index 1: CATS
        assertThat(categories.get(1).getCatId()).isEqualTo("CATS");
        assertThat(categories.get(1).getName()).isEqualTo("Cats");
        assertThat(categories.get(1).getDescription())
                .isEqualTo("<image src=\"../images/cats_icon.gif\"><font size=\"5\" color=\"blue\"> Cats</font>");

        // Index 2: DOGS
        assertThat(categories.get(2).getCatId()).isEqualTo("DOGS");
        assertThat(categories.get(2).getName()).isEqualTo("Dogs");
        assertThat(categories.get(2).getDescription())
                .isEqualTo("<image src=\"../images/dogs_icon.gif\"><font size=\"5\" color=\"blue\"> Dogs</font>");

        // Index 3: FISH
        assertThat(categories.get(3).getCatId()).isEqualTo("FISH");
        assertThat(categories.get(3).getName()).isEqualTo("Fish");
        assertThat(categories.get(3).getDescription())
                .isEqualTo("<image src=\"../images/fish_icon.gif\"><font size=\"5\" color=\"blue\"> Fish</font>");

        // Index 4: REPTILES
        assertThat(categories.get(4).getCatId()).isEqualTo("REPTILES");
        assertThat(categories.get(4).getName()).isEqualTo("Reptiles");
        assertThat(categories.get(4).getDescription())
                .isEqualTo("<image src=\"../images/reptiles_icon.gif\"><font size=\"5\" color=\"blue\"> Reptiles</font>");
    }

    /**
     * Tests {@link CategoryRepository#findById(Object)} with an existing category ID —
     * replaces the monolith's {@code CategoryMapperTest.getCategory()} (lines 71-84).
     *
     * <p>Verifies that looking up a known category ("BIRDS") returns a present
     * Optional containing the correct entity with all fields matching the seed
     * data. Unlike the monolith's mapper which returned a nullable
     * {@code Category}, the JPA repository returns {@code Optional<Category>}.</p>
     *
     * <p>Key migration detail: uses {@code getCatId()} instead of the
     * monolith's {@code getCategoryId()}, and the description HTML markup is
     * preserved exactly.</p>
     */
    @Test
    void testFindById() {
        // when
        Optional<Category> result = categoryRepository.findById("BIRDS");

        // then
        assertThat(result).isPresent();

        Category category = result.get();
        assertThat(category.getCatId()).isEqualTo("BIRDS");
        assertThat(category.getName()).isEqualTo("Birds");
        assertThat(category.getDescription())
                .isEqualTo("<image src=\"../images/birds_icon.gif\"><font size=\"5\" color=\"blue\"> Birds</font>");
    }

    /**
     * Tests {@link CategoryRepository#findById(Object)} with a non-existent
     * category ID — new test not present in the monolith's
     * {@code CategoryMapperTest}.
     *
     * <p>Verifies that looking up a non-existent category returns an empty
     * Optional, exercising the JPA repository's default behavior for missing
     * entities. This test was added because the monolith's MyBatis mapper
     * returned {@code null} for missing entities (which was not explicitly
     * tested), whereas the JPA repository returns {@code Optional.empty()}.</p>
     */
    @Test
    void testFindByIdNotFound() {
        // when
        Optional<Category> result = categoryRepository.findById("NONEXISTENT");

        // then
        assertThat(result).isEmpty();
    }
}
