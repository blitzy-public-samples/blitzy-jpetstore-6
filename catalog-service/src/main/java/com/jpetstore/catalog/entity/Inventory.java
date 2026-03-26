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
package com.jpetstore.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;

/**
 * JPA entity mapping for the {@code inventory} table in the Catalog Service's PostgreSQL database.
 *
 * <p>In the original JPetStore monolith, inventory quantity was stored in the {@code INVENTORY}
 * table and populated onto {@code Item.java} via a MyBatis JOIN query in {@code ItemMapper.xml}:
 * <pre>
 *   SELECT QTY AS quantity FROM ITEM I, INVENTORY V WHERE I.ITEMID = V.ITEMID
 * </pre>
 *
 * <p>In the decomposed microservices architecture, {@code Inventory} is a dedicated JPA entity
 * that owns the quantity-in-stock data independently from the {@code Item} entity. This separation
 * enables:
 * <ul>
 *   <li>Optimistic locking via {@link jakarta.persistence.Version @Version} to prevent lost updates
 *       during concurrent inventory decrements (replacing the non-locking MyBatis
 *       {@code UPDATE INVENTORY SET QTY = QTY - #{increment}} pattern)</li>
 *   <li>Independent lifecycle management for inventory operations (e.g., stock replenishment)
 *       without touching the item catalog</li>
 *   <li>Clear ownership boundary within the Catalog/Inventory bounded context</li>
 * </ul>
 *
 * <h3>Schema Reference</h3>
 * <pre>
 * CREATE TABLE inventory (
 *     itemid  VARCHAR(10) NOT NULL,
 *     qty     INT         NOT NULL,
 *     version BIGINT,                 -- Added for optimistic locking (not in original HSQLDB schema)
 *     CONSTRAINT pk_inventory PRIMARY KEY (itemid)
 * );
 * </pre>
 *
 * <h3>Concurrency Safety</h3>
 * The {@code @Version} field enables optimistic locking. When two concurrent transactions attempt
 * to decrement inventory for the same item, the second transaction will receive an
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}, allowing the
 * {@code InventoryService} to implement retry logic rather than silently losing an update
 * (which could lead to overselling).
 *
 * @see com.jpetstore.catalog.entity.Item
 */
@Entity
@Table(name = "inventory")
public class Inventory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The item identifier serving as the primary key.
     *
     * <p>This corresponds to the {@code itemid} column in the inventory table. While this value
     * matches the {@code itemid} in the {@code item} table, it is stored as a plain {@code String}
     * rather than a JPA {@code @ManyToOne} relationship. This design decision keeps the Inventory
     * entity independent from the Item entity for flexibility and simpler lifecycle management.
     *
     * <p>Maps from HSQLDB schema: {@code itemid VARCHAR(10) NOT NULL PRIMARY KEY}
     */
    @Id
    @Column(name = "itemid", length = 10, nullable = false)
    private String itemId;

    /**
     * The current quantity in stock for this item.
     *
     * <p>Maps from HSQLDB schema: {@code qty INT NOT NULL}
     *
     * <p>Corresponds to the monolith's MyBatis operations:
     * <ul>
     *   <li>{@code SELECT QTY AS value FROM INVENTORY WHERE ITEMID = #{itemId}}
     *       (getInventoryQuantity)</li>
     *   <li>{@code UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}}
     *       (updateInventoryQuantity — now replaced by JPA-managed update with optimistic lock)</li>
     * </ul>
     */
    @Column(name = "qty", nullable = false)
    private int qty;

    /**
     * Optimistic locking version field.
     *
     * <p><strong>This is a NEW field</strong> not present in the original HSQLDB schema.
     * It is added to enable optimistic locking via JPA's {@code @Version} mechanism,
     * replacing the non-thread-safe MyBatis {@code UPDATE INVENTORY SET QTY = QTY - #{increment}}
     * pattern that could result in lost updates under concurrent access.
     *
     * <p>On concurrent modification, Spring Data JPA throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * allowing the {@code InventoryService} to retry the operation.
     *
     * <p>The {@code version} column is created by the Liquibase migration
     * ({@code 001-initial-schema.xml}) in the Catalog Service's PostgreSQL database.
     */
    @Version
    @Column(name = "version")
    private Integer version;

    /**
     * Default no-argument constructor required by JPA.
     */
    public Inventory() {
        // Required by JPA specification for entity instantiation
    }

    /**
     * Returns the item identifier (primary key).
     *
     * @return the item ID, or {@code null} if not yet set
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets the item identifier (primary key).
     *
     * <p>The value is trimmed for consistency with other catalog entities
     * (e.g., {@code Item.setItemId()}, {@code Category.setCatId()}) which
     * trim their ID values to prevent whitespace-related lookup failures.
     *
     * @param itemId the item ID to set; if {@code null}, the field is set to {@code null}
     */
    public void setItemId(String itemId) {
        this.itemId = (itemId != null) ? itemId.trim() : null;
    }

    /**
     * Returns the current quantity in stock.
     *
     * @return the inventory quantity
     */
    public int getQty() {
        return qty;
    }

    /**
     * Sets the current quantity in stock.
     *
     * @param qty the inventory quantity to set
     */
    public void setQty(int qty) {
        this.qty = qty;
    }

    /**
     * Returns the optimistic locking version.
     *
     * <p>This value is managed by the JPA provider (Hibernate) and should not
     * normally be set manually by application code. It is incremented automatically
     * on each successful update.
     *
     * @return the current version, or {@code null} for a new (transient) entity
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * Sets the optimistic locking version.
     *
     * <p><strong>Warning:</strong> This setter exists for JPA framework use and
     * testing purposes. Application code should generally not call this method
     * directly, as the version is managed by the JPA provider.
     *
     * @param version the version to set
     */
    public void setVersion(Integer version) {
        this.version = version;
    }

    /**
     * Returns a string representation of this inventory record.
     *
     * @return a string in the format {@code Inventory{itemId='<id>', qty=<qty>}}
     */
    @Override
    public String toString() {
        return "Inventory{itemId='" + itemId + "', qty=" + qty + "}";
    }

}
