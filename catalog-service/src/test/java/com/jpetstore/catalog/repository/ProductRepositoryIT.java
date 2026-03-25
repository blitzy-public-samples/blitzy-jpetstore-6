/*
 * JPetStore Catalog Service — ProductRepository Integration Test
 *
 * Migrated from the monolith's org.mybatis.jpetstore.mapper.ProductMapperTest.
 * Tests ProductRepository's findByCategoryCatId() (products by category),
 * findById() (single product lookup), and searchByName() (keyword search
 * with % wildcard matching LOWER(name)) against seeded product data using
 * Testcontainers PostgreSQL 16 instead of the monolith's embedded HSQLDB.
 *
 * Migration mapping:
 *   ProductMapper.getProductListByCategory(String) → ProductRepository.findByCategoryCatId(String)
 *   ProductMapper.getProduct(String)               → ProductRepository.findById(String) → Optional<Product>
 *   ProductMapper.searchProductList(String)         → ProductRepository.searchByName(String) — @Query JPQL
 *
 * Key differences from monolith test:
 *   - Uses Testcontainers PostgreSQL 16 instead of HSQLDB (MapperTestContext.java)
 *   - Uses Spring Data JPA (JpaRepository) instead of MyBatis mapper
 *   - Product entity has @ManyToOne Category relationship — category accessed via
 *     product.getCategory().getCatId() instead of product.getCategoryId()
 *   - findById returns Optional<Product> instead of nullable Product
 *   - @SpringBootTest loads full context instead of @ContextConfiguration(MapperTestContext.class)
 *   - @DynamicPropertySource wires Testcontainers instead of embedded DB builder
 *   - Added testFindByIdNotFound() (not in monolith test)
 *   - @BeforeEach seeds all 5 categories and 16 products (monolith used SQL scripts)
 *
 * Test data:
 *   Categories seeded from jpetstore-hsqldb-dataload.sql lines 34-38 (5 INSERTs)
 *   Products seeded from jpetstore-hsqldb-dataload.sql lines 40-55 (16 INSERTs)
 *   Categories must be persisted before products due to @ManyToOne FK constraint.
 *
 * @see com.jpetstore.catalog.repository.ProductRepository
 * @see com.jpetstore.catalog.entity.Product
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
import com.jpetstore.catalog.entity.Product;

/**
 * Integration test for {@link ProductRepository} verifying Spring Data JPA
 * repository operations against a real PostgreSQL 16 database via Testcontainers.
 *
 * <p>This test class replaces the monolith's {@code ProductMapperTest} which
 * tested the MyBatis {@code ProductMapper} against an embedded HSQLDB instance
 * configured via {@code MapperTestContext}. The migration preserves all original
 * test assertions while adapting to the new JPA-based persistence layer.</p>
 *
 * <p>The {@code @Transactional} annotation ensures each test method executes
 * within a transaction that is rolled back after completion, providing test
 * isolation identical to the monolith's {@code ProductMapperTest} pattern.
 * It also enables lazy loading of JPA entity relationships (Product → Category)
 * within the test transaction boundary.</p>
 *
 * <p>Test data is seeded in {@link #setUp()} from the exact same values as the
 * monolith's {@code jpetstore-hsqldb-dataload.sql} (lines 34-55), ensuring
 * behavioral parity between the original MyBatis mapper and the new JPA
 * repository.</p>
 */
@SpringBootTest
@Testcontainers
@Transactional
class ProductRepositoryIT {

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
     * JPA entities define the schema, and disables Liquibase for test isolation.
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
     * {@code ProductMapper} MyBatis mapper interface.
     */
    @Autowired
    private ProductRepository productRepository;

    /**
     * Spring Data JPA repository for {@link Category} entities, injected to
     * persist parent Category entities required by Product's {@code @ManyToOne}
     * foreign key relationship before seeding products.
     */
    @Autowired
    private CategoryRepository categoryRepository;

    // ========================================================================
    // Category references used for Product @ManyToOne assignment
    // ========================================================================
    private Category fish;
    private Category dogs;
    private Category reptiles;
    private Category cats;
    private Category birds;

    /**
     * Seeds the test database with 5 categories and 16 products before each
     * test method. Categories are persisted first because products have a
     * {@code @ManyToOne} FK relationship to Category.
     *
     * <p>Data matches exactly the monolith's {@code jpetstore-hsqldb-dataload.sql}:
     * categories from lines 34-38 and products from lines 40-55.</p>
     *
     * <p>Product descriptions contain HTML markup with {@code <image>} tags
     * that must be preserved exactly as in the monolith.</p>
     */
    @BeforeEach
    void setUp() {
        // Clear in FK-safe order: products first (has FK to category), then categories
        productRepository.deleteAll();
        categoryRepository.deleteAll();

        // ====================================================================
        // Seed 5 categories (from jpetstore-hsqldb-dataload.sql lines 34-38)
        // ====================================================================
        fish = new Category();
        fish.setCatId("FISH");
        fish.setName("Fish");
        fish.setDescription("<image src=\"../images/fish_icon.gif\"><font size=\"5\" color=\"blue\"> Fish</font>");

        dogs = new Category();
        dogs.setCatId("DOGS");
        dogs.setName("Dogs");
        dogs.setDescription("<image src=\"../images/dogs_icon.gif\"><font size=\"5\" color=\"blue\"> Dogs</font>");

        reptiles = new Category();
        reptiles.setCatId("REPTILES");
        reptiles.setName("Reptiles");
        reptiles.setDescription("<image src=\"../images/reptiles_icon.gif\"><font size=\"5\" color=\"blue\"> Reptiles</font>");

        cats = new Category();
        cats.setCatId("CATS");
        cats.setName("Cats");
        cats.setDescription("<image src=\"../images/cats_icon.gif\"><font size=\"5\" color=\"blue\"> Cats</font>");

        birds = new Category();
        birds.setCatId("BIRDS");
        birds.setName("Birds");
        birds.setDescription("<image src=\"../images/birds_icon.gif\"><font size=\"5\" color=\"blue\"> Birds</font>");

        categoryRepository.saveAll(List.of(fish, dogs, reptiles, cats, birds));
        categoryRepository.flush();

        // ====================================================================
        // Seed 16 products (from jpetstore-hsqldb-dataload.sql lines 40-55)
        // ====================================================================

        // FISH products (4)
        Product fiSw01 = createProduct("FI-SW-01", fish, "Angelfish",
                "<image src=\"../images/fish1.gif\">Salt Water fish from Australia");
        Product fiSw02 = createProduct("FI-SW-02", fish, "Tiger Shark",
                "<image src=\"../images/fish4.gif\">Salt Water fish from Australia");
        Product fiFw01 = createProduct("FI-FW-01", fish, "Koi",
                "<image src=\"../images/fish3.gif\">Fresh Water fish from Japan");
        Product fiFw02 = createProduct("FI-FW-02", fish, "Goldfish",
                "<image src=\"../images/fish2.gif\">Fresh Water fish from China");

        // DOGS products (6)
        Product k9Bd01 = createProduct("K9-BD-01", dogs, "Bulldog",
                "<image src=\"../images/dog2.gif\">Friendly dog from England");
        Product k9Po02 = createProduct("K9-PO-02", dogs, "Poodle",
                "<image src=\"../images/dog6.gif\">Cute dog from France");
        Product k9Dl01 = createProduct("K9-DL-01", dogs, "Dalmation",
                "<image src=\"../images/dog5.gif\">Great dog for a Fire Station");
        Product k9Rt01 = createProduct("K9-RT-01", dogs, "Golden Retriever",
                "<image src=\"../images/dog1.gif\">Great family dog");
        Product k9Rt02 = createProduct("K9-RT-02", dogs, "Labrador Retriever",
                "<image src=\"../images/dog5.gif\">Great hunting dog");
        Product k9Cw01 = createProduct("K9-CW-01", dogs, "Chihuahua",
                "<image src=\"../images/dog4.gif\">Great companion dog");

        // REPTILES products (2)
        Product rpSn01 = createProduct("RP-SN-01", reptiles, "Rattlesnake",
                "<image src=\"../images/snake1.gif\">Doubles as a watch dog");
        Product rpLi02 = createProduct("RP-LI-02", reptiles, "Iguana",
                "<image src=\"../images/lizard1.gif\">Friendly green friend");

        // CATS products (2)
        Product flDsh01 = createProduct("FL-DSH-01", cats, "Manx",
                "<image src=\"../images/cat2.gif\">Great for reducing mouse populations");
        Product flDlh02 = createProduct("FL-DLH-02", cats, "Persian",
                "<image src=\"../images/cat1.gif\">Friendly house cat, doubles as a princess");

        // BIRDS products (2)
        Product avCb01 = createProduct("AV-CB-01", birds, "Amazon Parrot",
                "<image src=\"../images/bird2.gif\">Great companion for up to 75 years");
        Product avSb02 = createProduct("AV-SB-02", birds, "Finch",
                "<image src=\"../images/bird1.gif\">Great stress reliever");

        productRepository.saveAll(List.of(
                fiSw01, fiSw02, fiFw01, fiFw02,
                k9Bd01, k9Po02, k9Dl01, k9Rt01, k9Rt02, k9Cw01,
                rpSn01, rpLi02,
                flDsh01, flDlh02,
                avCb01, avSb02
        ));
        productRepository.flush();
    }

    /**
     * Helper method to create a Product entity with the given fields.
     * Uses setCategory(Category) to satisfy the @ManyToOne FK relationship.
     *
     * @param productId   the product identifier (e.g., "FI-SW-01")
     * @param category    the parent Category entity reference
     * @param name        the product display name (e.g., "Angelfish")
     * @param description the product description including HTML markup
     * @return a fully populated Product entity ready for persistence
     */
    private Product createProduct(String productId, Category category, String name, String description) {
        Product product = new Product();
        product.setProductId(productId);
        product.setCategory(category);
        product.setName(name);
        product.setDescription(description);
        return product;
    }

    // ========================================================================
    // Test Methods
    // ========================================================================

    /**
     * Tests {@link ProductRepository#findByCategoryCatId(String)} — replaces the
     * monolith's {@code ProductMapperTest.getProductListByCategory()} (lines 40-70).
     *
     * <p>Verifies that querying products by category ID "FISH" returns exactly
     * 4 products with correct productId, name, description, and category
     * relationship fields. Results are sorted by productId for deterministic
     * ordering, matching the monolith's assertion pattern at line 48.</p>
     *
     * <p>Key migration detail: uses {@code product.getCategory().getCatId()}
     * instead of the monolith's {@code product.getCategoryId()}, navigating the
     * JPA {@code @ManyToOne} relationship to verify the FK chain.</p>
     */
    @Test
    void testFindByCategoryCatId() {
        // given
        String categoryId = "FISH";

        // when
        List<Product> products = productRepository.findByCategoryCatId(categoryId);

        // then — sort by productId for deterministic ordering
        // (mirrors monolith's Comparator.comparing(Product::getProductId) at line 48)
        products.sort(Comparator.comparing(Product::getProductId));

        assertThat(products).hasSize(4);

        // Index 0: FI-FW-01 (Koi) — matches monolith lines 50-54
        assertThat(products.get(0).getProductId()).isEqualTo("FI-FW-01");
        assertThat(products.get(0).getName()).isEqualTo("Koi");
        assertThat(products.get(0).getCategory().getCatId()).isEqualTo("FISH");
        assertThat(products.get(0).getDescription())
                .isEqualTo("<image src=\"../images/fish3.gif\">Fresh Water fish from Japan");

        // Index 1: FI-FW-02 (Goldfish) — matches monolith lines 55-59
        assertThat(products.get(1).getProductId()).isEqualTo("FI-FW-02");
        assertThat(products.get(1).getName()).isEqualTo("Goldfish");
        assertThat(products.get(1).getCategory().getCatId()).isEqualTo("FISH");
        assertThat(products.get(1).getDescription())
                .isEqualTo("<image src=\"../images/fish2.gif\">Fresh Water fish from China");

        // Index 2: FI-SW-01 (Angelfish) — matches monolith lines 60-64
        assertThat(products.get(2).getProductId()).isEqualTo("FI-SW-01");
        assertThat(products.get(2).getName()).isEqualTo("Angelfish");
        assertThat(products.get(2).getCategory().getCatId()).isEqualTo("FISH");
        assertThat(products.get(2).getDescription())
                .isEqualTo("<image src=\"../images/fish1.gif\">Salt Water fish from Australia");

        // Index 3: FI-SW-02 (Tiger Shark) — matches monolith lines 65-69
        assertThat(products.get(3).getProductId()).isEqualTo("FI-SW-02");
        assertThat(products.get(3).getName()).isEqualTo("Tiger Shark");
        assertThat(products.get(3).getCategory().getCatId()).isEqualTo("FISH");
        assertThat(products.get(3).getDescription())
                .isEqualTo("<image src=\"../images/fish4.gif\">Salt Water fish from Australia");
    }

    /**
     * Tests {@link ProductRepository#findById(Object)} with an existing product ID —
     * replaces the monolith's {@code ProductMapperTest.getProduct()} (lines 73-85).
     *
     * <p>Verifies that looking up a known product ("FI-FW-01") returns a present
     * Optional containing the correct entity with all fields matching the seed
     * data. Unlike the monolith's mapper which returned a nullable
     * {@code Product}, the JPA repository returns {@code Optional<Product>}.</p>
     *
     * <p>Key migration detail: uses {@code product.getCategory().getCatId()}
     * instead of the monolith's {@code product.getCategoryId()}, and the
     * description field (mapped to DB column "descn") is verified exactly.</p>
     */
    @Test
    void testFindById() {
        // given
        String productId = "FI-FW-01";

        // when
        Optional<Product> result = productRepository.findById(productId);

        // then — matches monolith lines 81-84
        assertThat(result).isPresent();

        Product product = result.orElseThrow();
        assertThat(product.getProductId()).isEqualTo("FI-FW-01");
        assertThat(product.getName()).isEqualTo("Koi");
        assertThat(product.getCategory().getCatId()).isEqualTo("FISH");
        assertThat(product.getDescription())
                .isEqualTo("<image src=\"../images/fish3.gif\">Fresh Water fish from Japan");
    }

    /**
     * Tests {@link ProductRepository#searchByName(String)} — replaces the
     * monolith's {@code ProductMapperTest.searchProductList()} (lines 87-111).
     *
     * <p>Verifies that searching for products whose LOWER(name) matches the
     * pattern "%o%" returns exactly 8 products. The repository's {@code @Query}
     * uses {@code LOWER(p.name) LIKE :keyword}, so the keyword "%o%" matches
     * lowercase 'o' in product names.</p>
     *
     * <p>Products matching (lowercase 'o' in name):
     * Amazon Parr<strong>o</strong>t, K<strong>o</strong>i,
     * G<strong>o</strong>ldfish, Bulld<strong>o</strong>g,
     * Dalmati<strong>o</strong>n, P<strong>oo</strong>dle,
     * G<strong>o</strong>lden Retriever, Labrad<strong>o</strong>r Retriever</p>
     *
     * <p>Key migration detail: uses {@code productRepository.searchByName("%o%")}
     * instead of the monolith's {@code mapper.searchProductList("%o%")}. The
     * category navigation chain is {@code getCategory().getCatId()} instead of
     * {@code getCategoryId()}.</p>
     */
    @Test
    void testSearchByName() {
        // given
        String keywords = "%o%";

        // when
        List<Product> products = productRepository.searchByName(keywords);

        // then — sort by productId for deterministic ordering
        // (mirrors monolith's Comparator.comparing(Product::getProductId) at line 96)
        products.sort(Comparator.comparing(Product::getProductId));

        assertThat(products).hasSize(8);

        // Index 0: AV-CB-01 (Amazon Parrot) — matches monolith lines 99-103
        assertThat(products.get(0).getProductId()).isEqualTo("AV-CB-01");
        assertThat(products.get(0).getName()).isEqualTo("Amazon Parrot");
        assertThat(products.get(0).getCategory().getCatId()).isEqualTo("BIRDS");
        assertThat(products.get(0).getDescription())
                .isEqualTo("<image src=\"../images/bird2.gif\">Great companion for up to 75 years");

        // Index 1: FI-FW-01 (Koi) — matches monolith line 104
        assertThat(products.get(1).getName()).isEqualTo("Koi");

        // Index 2: FI-FW-02 (Goldfish) — matches monolith line 105
        assertThat(products.get(2).getName()).isEqualTo("Goldfish");

        // Index 3: K9-BD-01 (Bulldog) — matches monolith line 106
        assertThat(products.get(3).getName()).isEqualTo("Bulldog");

        // Index 4: K9-DL-01 (Dalmation) — matches monolith line 107
        assertThat(products.get(4).getName()).isEqualTo("Dalmation");

        // Index 5: K9-PO-02 (Poodle) — matches monolith line 108
        assertThat(products.get(5).getName()).isEqualTo("Poodle");

        // Index 6: K9-RT-01 (Golden Retriever) — matches monolith line 109
        assertThat(products.get(6).getName()).isEqualTo("Golden Retriever");

        // Index 7: K9-RT-02 (Labrador Retriever) — matches monolith line 110
        assertThat(products.get(7).getName()).isEqualTo("Labrador Retriever");
    }

    /**
     * Tests {@link ProductRepository#findById(Object)} with a non-existent
     * product ID — new test not present in the monolith's
     * {@code ProductMapperTest}.
     *
     * <p>Verifies that looking up a non-existent product returns an empty
     * Optional, exercising the JPA repository's default behavior for missing
     * entities. This test was added because the monolith's MyBatis mapper
     * returned {@code null} for missing entities (which was not explicitly
     * tested), whereas the JPA repository returns {@code Optional.empty()}.</p>
     */
    @Test
    void testFindByIdNotFound() {
        // when
        Optional<Product> result = productRepository.findById("NONEXISTENT");

        // then
        assertThat(result).isEmpty();
    }
}
