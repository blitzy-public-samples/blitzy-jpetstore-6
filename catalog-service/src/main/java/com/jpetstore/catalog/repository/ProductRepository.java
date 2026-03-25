/*
 * JPetStore Catalog Service — Product Spring Data JPA Repository
 *
 * Replaces the monolith's org.mybatis.jpetstore.mapper.ProductMapper interface
 * (3 methods) with a Spring Data JPA repository backed by PostgreSQL.
 *
 * Method mapping (MyBatis mapper → Spring Data JPA repository):
 *
 *   ProductMapper.getProduct(String productId)
 *     → JpaRepository.findById(String) — built-in, no custom declaration needed
 *
 *   ProductMapper.getProductListByCategory(String categoryId)
 *     → ProductRepository.findByCategoryCatId(String catId) — derived query
 *       navigating Product.category (@ManyToOne) → Category.catId (@Id)
 *
 *   ProductMapper.searchProductList(String keywords)
 *     → ProductRepository.searchByName(String keyword) — custom @Query with JPQL
 *       performing case-insensitive LIKE match on Product.name
 *
 * The original MyBatis mapper XML had L2 cache enabled (<cache />).
 * Caching is intentionally NOT replicated at the repository level; if needed,
 * it should be applied at the service layer using Spring @Cacheable annotations.
 *
 * Important contract notes:
 *   - The searchByName method expects the caller to supply '%' wildcards and
 *     lowercase the keyword before invocation, matching the original pattern
 *     in CatalogService.searchProductList() (line 74):
 *       productMapper.searchProductList("%" + keyword.toLowerCase() + "%")
 *   - Keyword tokenization (splitting by whitespace) is a service-layer concern;
 *     this repository handles a single-keyword search per invocation.
 *   - The Product entity's primary key (productId) is a String, so the
 *     JpaRepository type parameters are <Product, String>.
 */
package com.jpetstore.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jpetstore.catalog.entity.Product;

/**
 * Spring Data JPA repository for {@link Product} entities.
 *
 * <p>Provides CRUD operations inherited from {@link JpaRepository} plus two
 * custom query methods that replicate the read operations previously defined
 * in the monolith's {@code ProductMapper} MyBatis interface.</p>
 *
 * <h3>Inherited methods (from JpaRepository)</h3>
 * <ul>
 *   <li>{@code findById(String)} — replaces {@code ProductMapper.getProduct(String)}</li>
 *   <li>{@code findAll()} — list all products</li>
 *   <li>{@code save(Product)} — persist a new or updated product</li>
 *   <li>{@code deleteById(String)} — remove a product by ID</li>
 * </ul>
 *
 * <h3>Custom methods</h3>
 * <ul>
 *   <li>{@link #findByCategoryCatId(String)} — derived query replacing
 *       {@code ProductMapper.getProductListByCategory(String)}</li>
 *   <li>{@link #searchByName(String)} — JPQL query replacing
 *       {@code ProductMapper.searchProductList(String)}</li>
 * </ul>
 *
 * <p>No {@code @Repository} annotation is required — Spring Data JPA
 * automatically detects interfaces extending {@link JpaRepository} during
 * component scanning and creates a proxy implementation at runtime.</p>
 *
 * @see Product
 * @see com.jpetstore.catalog.entity.Category
 */
public interface ProductRepository extends JpaRepository<Product, String> {

    /**
     * Finds all products belonging to the specified category.
     *
     * <p>This is a <em>derived query method</em>. Spring Data JPA parses the
     * method name to generate the query automatically:</p>
     * <ul>
     *   <li>{@code findBy} — query prefix</li>
     *   <li>{@code Category} — navigates to the {@code Product.category} field
     *       (a {@code @ManyToOne} relationship to {@link com.jpetstore.catalog.entity.Category})</li>
     *   <li>{@code CatId} — accesses the {@code Category.catId} field
     *       (the {@code @Id} primary key of the Category entity)</li>
     * </ul>
     *
     * <p>Generated JPQL equivalent:
     * {@code SELECT p FROM Product p WHERE p.category.catId = :catId}</p>
     *
     * <p>Replaces the monolith's MyBatis SQL:
     * {@code SELECT ... FROM PRODUCT WHERE CATEGORY = #{value}}</p>
     *
     * @param catId the category identifier to filter by (e.g., "FISH", "DOGS");
     *              must not be {@code null}
     * @return a list of products in the specified category; empty list if none found
     */
    List<Product> findByCategoryCatId(String catId);

    /**
     * Searches for products whose name matches the given keyword pattern
     * using a case-insensitive LIKE comparison.
     *
     * <p>The caller is responsible for wrapping the keyword with SQL wildcard
     * characters ({@code %}) and converting it to lowercase before invocation.
     * This matches the original calling convention in
     * {@code CatalogService.searchProductList()}:</p>
     * <pre>{@code
     * productMapper.searchProductList("%" + keyword.toLowerCase() + "%");
     * }</pre>
     *
     * <p>Example usage from the service layer:</p>
     * <pre>{@code
     * List<Product> results = productRepository.searchByName("%" + term.toLowerCase() + "%");
     * }</pre>
     *
     * <p>Replaces the monolith's MyBatis SQL:
     * {@code SELECT ... FROM PRODUCT WHERE lower(name) LIKE #{value}}</p>
     *
     * <p><strong>Note on keyword tokenization:</strong> The original
     * {@code CatalogService.searchProductList(String keywords)} splits the
     * input by whitespace and calls the mapper once per keyword. That
     * tokenization logic belongs in the service layer — this repository
     * method handles a single keyword search per invocation.</p>
     *
     * @param keyword the search pattern including {@code %} wildcards and
     *                already lowercased (e.g., {@code "%angel%"});
     *                must not be {@code null}
     * @return a list of products whose lowercased name matches the pattern;
     *         empty list if none found
     */
    @Query("SELECT p FROM Product p WHERE LOWER(p.name) LIKE :keyword")
    List<Product> searchByName(@Param("keyword") String keyword);

}
