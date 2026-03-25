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
package com.jpetstore.order.repository;

import java.util.List;

import com.jpetstore.order.entity.LineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link LineItem} entity, mapped to the {@code lineitem}
 * table in the Order Service's PostgreSQL database.
 *
 * <p>This repository replaces the monolith's {@code org.mybatis.jpetstore.mapper.LineItemMapper}
 * interface, which provided two MyBatis-mapped methods:</p>
 * <ul>
 *   <li>{@code getLineItemsByOrderId(int orderId)} — replaced by {@link #findByOrderId(int)}</li>
 *   <li>{@code insertLineItem(LineItem lineItem)} — replaced by the inherited
 *       {@link JpaRepository#save(Object)} method</li>
 * </ul>
 *
 * <p><strong>Composite Primary Key</strong>: The {@code lineitem} table uses a composite
 * primary key of {@code (order_id, line_num)}, represented by the
 * {@link LineItem.LineItemId} static inner class. This class is specified as the
 * {@code ID} type parameter of {@link JpaRepository}.</p>
 *
 * <p><strong>Cross-Service FK Removed</strong>: Per the AAP, the monolith's
 * {@code lineitem.itemid → item.itemid} foreign key constraint is removed at the database
 * level. The {@code item_id} column is stored as a plain {@code String} field in the
 * {@link LineItem} entity with no JPA relationship to any Catalog Service entity. Item
 * details are fetched via {@code CatalogServiceClient} REST calls when needed.</p>
 *
 * <p><strong>Intra-Service FK Preserved</strong>: The {@code lineitem.order_id → orders.order_id}
 * foreign key is maintained within the Order Service's PostgreSQL database, enforced via the
 * Liquibase migration changeset.</p>
 *
 * <p><strong>Inherited CRUD Operations</strong> (from {@link JpaRepository}):</p>
 * <ul>
 *   <li>{@code save(LineItem)} — persists a single line item (INSERT or UPDATE)</li>
 *   <li>{@code saveAll(Iterable<LineItem>)} — batch persists all line items for an order,
 *       replacing the monolith's loop in {@code OrderService.insertOrder()} that called
 *       {@code insertLineItem()} N times</li>
 *   <li>{@code findById(LineItem.LineItemId)} — retrieves a specific line item by composite key</li>
 *   <li>{@code findAll()} — retrieves all line items across all orders</li>
 *   <li>{@code deleteById(LineItem.LineItemId)} — deletes a specific line item by composite key</li>
 *   <li>{@code count()} — returns the total number of line item records</li>
 * </ul>
 *
 * <p><strong>No N+1 Query Risk</strong>: Since the {@code item_id} field is a plain String
 * with no JPA {@code @ManyToOne} relationship, there is no lazy-loading or N+1 query risk
 * associated with fetching item details through this repository.</p>
 *
 * @author Blitzy Platform
 * @see LineItem
 * @see LineItem.LineItemId
 * @see JpaRepository
 */
@Repository
public interface LineItemRepository extends JpaRepository<LineItem, LineItem.LineItemId> {

    /**
     * Retrieves all line items belonging to the specified order.
     *
     * <p>This is a Spring Data JPA derived query method that generates the equivalent of:</p>
     * <pre>{@code SELECT * FROM lineitem WHERE order_id = :orderId}</pre>
     *
     * <p>Directly replaces the monolith's {@code LineItemMapper.getLineItemsByOrderId(int orderId)}
     * which executed:</p>
     * <pre>{@code
     * SELECT ORDERID, LINENUM AS lineNumber, ITEMID, QUANTITY, UNITPRICE
     * FROM LINEITEM WHERE ORDERID = #{orderId}
     * }</pre>
     *
     * <p>The method name {@code findByOrderId} follows Spring Data JPA naming conventions:
     * {@code findBy} + property name {@code orderId} from the {@link LineItem} entity, which
     * maps to the {@code order_id} column via {@code @Column(name = "order_id")}.</p>
     *
     * @param orderId the order identifier to filter line items by
     * @return a list of {@link LineItem} entities for the given order, or an empty list
     *         if no line items exist for the order
     */
    List<LineItem> findByOrderId(int orderId);
}
