/*
 * Copyright 2010-2026 the original author or authors.
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
package com.jpetstore.order.repository;

import java.util.List;

import com.jpetstore.order.entity.OrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link OrderStatus} entity mapped to the
 * {@code orderstatus} table in the Order Service's PostgreSQL database
 * ({@code jpetstore_order}).
 *
 * <p>This repository extracts order-status persistence operations that were
 * previously embedded within the monolith's {@code OrderMapper.xml}:
 * <ul>
 *   <li>{@link #save(OrderStatus)} replaces {@code OrderMapper.insertOrderStatus(Order)}
 *       (OrderMapper.xml lines 104–107). <strong>Note:</strong> the monolith inserts
 *       {@code orderId} as both {@code ORDERID} and {@code LINENUM}; in the
 *       microservice the service layer must replicate this behavior when
 *       constructing the {@code OrderStatus} entity to maintain data
 *       compatibility.</li>
 *   <li>{@link #findByOrderId(int)} replaces the implicit JOIN in
 *       {@code OrderMapper.getOrder()} (OrderMapper.xml lines 54–56) that
 *       fetched orderstatus rows alongside the order record.</li>
 * </ul>
 *
 * <h3>Composite Primary Key</h3>
 * <p>The {@code orderstatus} table uses a composite primary key
 * ({@code order_id}, {@code line_num}). The entity models this via
 * {@link jakarta.persistence.IdClass} with the static inner class
 * {@link OrderStatus.OrderStatusId}. Accordingly, this repository's ID type
 * parameter is {@code OrderStatus.OrderStatusId}.
 *
 * <h3>Inherited CRUD Operations</h3>
 * <p>Standard persistence methods are inherited from {@link JpaRepository}:
 * <ul>
 *   <li>{@code save(OrderStatus)} — persist or merge an order-status record</li>
 *   <li>{@code findById(OrderStatus.OrderStatusId)} — look up by composite key</li>
 *   <li>{@code findAll()} — retrieve all order-status records</li>
 *   <li>{@code deleteById(OrderStatus.OrderStatusId)} — remove by composite key</li>
 *   <li>{@code deleteAll()} — remove all order-status records</li>
 *   <li>{@code count()} — count all order-status records</li>
 * </ul>
 *
 * <h3>Intra-Service Foreign Key</h3>
 * <p>{@code orderstatus.order_id} logically references {@code orders.order_id}
 * within the same Order Service database. The referential constraint is
 * enforced at the database level via Liquibase, not through a JPA
 * {@code @ManyToOne} mapping.
 *
 * @see OrderStatus
 * @see OrderStatus.OrderStatusId
 */
@Repository
public interface OrderStatusRepository extends JpaRepository<OrderStatus, OrderStatus.OrderStatusId> {

    /**
     * Finds all order-status records for the specified order.
     *
     * <p>This Spring Data JPA derived query method automatically generates:
     * <pre>{@code SELECT * FROM orderstatus WHERE order_id = ?}</pre>
     *
     * <p>It replaces the monolith's {@code OrderMapper.getOrder()} JOIN pattern
     * (OrderMapper.xml lines 54–56):
     * <pre>{@code
     * FROM ORDERS, ORDERSTATUS
     * WHERE ORDERS.ORDERID = #{value}
     *   AND ORDERS.ORDERID = ORDERSTATUS.ORDERID
     * }</pre>
     *
     * <p>In the microservice architecture, the Order Service calls this method
     * separately when loading order details, instead of relying on a single
     * SQL JOIN across the orders and orderstatus tables.
     *
     * @param orderId the order identifier to look up status records for
     * @return a list of {@link OrderStatus} entries for the given order,
     *         or an empty list if no records exist
     */
    List<OrderStatus> findByOrderId(int orderId);
}
