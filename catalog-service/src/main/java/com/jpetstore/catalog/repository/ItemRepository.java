/*
 * JPetStore Catalog Service — Item Spring Data JPA Repository
 *
 * Replaces the read-only query methods from the monolith's
 * org.mybatis.jpetstore.mapper.ItemMapper interface. Specifically, this
 * repository provides equivalents for the following two ItemMapper methods:
 *
 *   1. List<Item> getItemListByProduct(String productId)
 *      → findByProductProductId(String productId)
 *        Derived query navigating Item.product.productId.
 *        @EntityGraph eagerly loads the Product association to prevent N+1 queries,
 *        matching the original MyBatis SQL that JOINed ITEM with PRODUCT.
 *
 *   2. Item getItem(String itemId)
 *      → findById(String itemId)   [overridden from JpaRepository]
 *        @EntityGraph eagerly loads both Product and Supplier associations in a
 *        single SQL JOIN, matching the original MyBatis SQL that JOINed ITEM,
 *        PRODUCT, and INVENTORY (the INVENTORY join is no longer relevant here
 *        because quantity is managed by the separate Inventory entity/repository).
 *
 * The remaining two ItemMapper methods (updateInventoryQuantity, getInventoryQuantity)
 * are NOT in this repository — they belong to InventoryRepository, which manages the
 * Inventory entity with @Version for optimistic locking on concurrent decrements.
 *
 * Design notes:
 *   - No @Repository annotation needed — Spring Data JPA auto-detects interfaces
 *     extending JpaRepository during component scanning.
 *   - No @Query annotations — derived query method names and @EntityGraph are
 *     sufficient for all read operations.
 *   - No @Modifying or @Transactional annotations — all methods are read-only.
 *   - The original ItemMapper.xml declared <cache /> (MyBatis L2 cache). In the
 *     microservice architecture, caching is applied at the service layer rather
 *     than the repository layer, using Spring Cache abstraction if needed.
 *   - Generic type parameters are <Item, String> because the Item entity uses
 *     @Id String itemId (from DB schema: itemid varchar(10) not null PK).
 */
package com.jpetstore.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.jpetstore.catalog.entity.Item;

/**
 * Spring Data JPA repository for {@link Item} entities in the Catalog bounded context.
 *
 * <p>Provides read-only query methods that replace the item-retrieval operations
 * from the monolith's {@code ItemMapper}. Each method uses {@link EntityGraph}
 * annotations to eagerly fetch related entities in a single SQL query, avoiding
 * the N+1 query problem that would otherwise occur with lazy-loaded
 * {@code @ManyToOne} associations.</p>
 *
 * <h3>Method mapping from monolith ItemMapper</h3>
 * <table>
 *   <tr><th>ItemMapper method</th><th>Repository method</th><th>Eager-loaded associations</th></tr>
 *   <tr>
 *     <td>{@code getItemListByProduct(String)}</td>
 *     <td>{@link #findByProductProductId(String)}</td>
 *     <td>Product</td>
 *   </tr>
 *   <tr>
 *     <td>{@code getItem(String)}</td>
 *     <td>{@link #findById(String)}</td>
 *     <td>Product, Supplier</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Note:</strong> The original {@code getItem} SQL also JOINed the
 * INVENTORY table to fetch {@code QTY AS quantity}. In the decomposed architecture,
 * inventory quantity is managed by {@code InventoryRepository} with its own
 * {@code Inventory} entity. The service layer is responsible for combining item
 * details with inventory data when both are needed.</p>
 *
 * @see Item
 * @see com.jpetstore.catalog.entity.Product
 * @see com.jpetstore.catalog.entity.Supplier
 */
public interface ItemRepository extends JpaRepository<Item, String> {

    /**
     * Finds all items that belong to the specified product.
     *
     * <p>This is a Spring Data JPA <strong>derived query method</strong>. The method
     * name is parsed as:</p>
     * <ul>
     *   <li>{@code findBy} — query prefix</li>
     *   <li>{@code Product} — navigates to the {@code Item.product} association</li>
     *   <li>{@code ProductId} — matches the {@code Product.productId} field</li>
     * </ul>
     *
     * <p>The generated JPQL is equivalent to:
     * {@code SELECT i FROM Item i WHERE i.product.productId = :productId}</p>
     *
     * <p>The {@link EntityGraph} annotation eagerly loads the {@code product}
     * association in a single SQL JOIN, matching the original MyBatis SQL that
     * performed {@code FROM ITEM I, PRODUCT P WHERE P.PRODUCTID = I.PRODUCTID}.
     * This prevents N+1 queries when the caller iterates over items and accesses
     * their product details.</p>
     *
     * <p>Replaces monolith's {@code ItemMapper.getItemListByProduct(String)}.</p>
     *
     * @param productId the product ID to filter by (e.g., "FI-SW-01")
     * @return a list of items belonging to the specified product; empty list if none found
     */
    @EntityGraph(attributePaths = {"product"})
    List<Item> findByProductProductId(String productId);

    /**
     * Finds an item by its unique identifier, eagerly loading associated entities.
     *
     * <p>Overrides the inherited {@link JpaRepository#findById(Object)} method to
     * add an {@link EntityGraph} that eagerly fetches both the {@code product} and
     * {@code supplier} associations in a single SQL query with JOINs. This replaces
     * the original MyBatis SQL in {@code ItemMapper.xml} that performed a three-way
     * JOIN across ITEM, PRODUCT, and INVENTORY tables.</p>
     *
     * <p>The INVENTORY join is no longer relevant in this repository because
     * inventory quantity is managed by the separate {@code Inventory} entity with
     * {@code @Version} for optimistic locking. The service layer combines item
     * details with inventory data when needed by querying {@code InventoryRepository}
     * separately.</p>
     *
     * <p>Including {@code "supplier"} in the entity graph matches the original
     * query's inclusion of the supplier FK and prevents a lazy-load proxy hit when
     * supplier details are accessed.</p>
     *
     * <p>Replaces monolith's {@code ItemMapper.getItem(String)}.</p>
     *
     * @param itemId the item ID to look up (e.g., "EST-1")
     * @return an {@link Optional} containing the item if found, or empty if not
     */
    @Override
    @EntityGraph(attributePaths = {"product", "supplier"})
    Optional<Item> findById(String itemId);

}
