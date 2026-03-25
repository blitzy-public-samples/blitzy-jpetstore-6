/*
 * JPetStore Catalog Service — Category Spring Data JPA Repository
 *
 * Replaces the monolith's org.mybatis.jpetstore.mapper.CategoryMapper interface,
 * which declared two methods backed by MyBatis XML-mapped SQL statements:
 *
 *   1. getCategoryList()       → now provided by JpaRepository.findAll()
 *   2. getCategory(String id)  → now provided by JpaRepository.findById(String)
 *
 * Both original mapper methods map directly to built-in JpaRepository operations,
 * so this interface requires NO custom method declarations. Spring Data JPA
 * auto-generates the implementation at runtime via a proxy.
 *
 * Original MyBatis SQL (from CategoryMapper.xml):
 *   getCategoryList:  SELECT CATID AS categoryId, NAME, DESCN AS description FROM CATEGORY
 *   getCategory:      SELECT CATID AS categoryId, NAME, DESCN AS description FROM CATEGORY WHERE CATID = #{categoryId}
 *
 * The column aliasing previously handled by MyBatis (CATID → categoryId, DESCN → description)
 * is now handled by JPA @Column annotations on the Category entity class.
 *
 * Note on caching: The original CategoryMapper.xml had L2 caching enabled via
 * <cache />. In the microservice architecture, caching is applied at the service
 * layer (e.g., Spring @Cacheable) rather than at the repository level, if needed.
 *
 * Note on return type difference: JpaRepository.findById(String) returns
 * Optional<Category>, whereas the original CategoryMapper.getCategory(String)
 * returned a nullable Category directly. The service layer handles the Optional
 * unwrapping via .orElse(null) or similar patterns.
 *
 * Design decisions:
 *   - No @Repository annotation — Spring Boot auto-detects JpaRepository sub-interfaces
 *   - No @Query annotations — all queries are JPA built-ins
 *   - No @Transactional annotations — transaction boundaries are managed by the service layer
 *   - Generic type parameters: Category (entity type), String (primary key type matching catId field)
 */
package com.jpetstore.catalog.repository;

import com.jpetstore.catalog.entity.Category;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Category} entities.
 *
 * <p>Provides standard CRUD operations for the {@code category} table in the
 * Catalog Service's PostgreSQL database. This interface replaces the monolith's
 * {@code org.mybatis.jpetstore.mapper.CategoryMapper} MyBatis mapper interface.</p>
 *
 * <h3>Method Mapping from Original CategoryMapper</h3>
 * <table>
 *   <tr><th>Original MyBatis Method</th><th>JpaRepository Equivalent</th></tr>
 *   <tr>
 *     <td>{@code List<Category> getCategoryList()}</td>
 *     <td>{@link JpaRepository#findAll()}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Category getCategory(String categoryId)}</td>
 *     <td>{@link JpaRepository#findById(Object) findById(String)}</td>
 *   </tr>
 * </table>
 *
 * <p>The entity's primary key field ({@code catId}) is of type {@code String},
 * which determines the second generic type parameter of this repository.</p>
 *
 * @see Category
 * @see JpaRepository
 */
public interface CategoryRepository extends JpaRepository<Category, String> {

    /*
     * No custom method declarations are needed.
     *
     * All two methods from the original CategoryMapper are satisfied by
     * JpaRepository's built-in methods:
     *
     *   - findAll()          → replaces getCategoryList()
     *     Returns List<Category> containing all categories.
     *
     *   - findById(String)   → replaces getCategory(String categoryId)
     *     Returns Optional<Category> for the given category ID (catId).
     *     The caller should use .orElse(null) or .orElseThrow() as appropriate.
     */

}
