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

import com.jpetstore.order.entity.CartState;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Redis repository for externalized cart state persistence.
 *
 * <p>This repository replaces the monolith's HTTP session-scoped {@code Cart} object
 * (stored in-memory via {@code Collections.synchronizedMap(new HashMap<>())} within
 * {@code CartActionBean}'s {@code @SessionScope}). In the microservices architecture,
 * cart state is externalized to Redis so that:
 * <ul>
 *   <li>The Order Service can manage cart data statelessly across multiple instances</li>
 *   <li>Cart state survives server restarts and load-balanced request routing</li>
 *   <li>Anonymous users (identified by session cookie) and authenticated users
 *       (identified by username) can maintain persistent carts</li>
 * </ul>
 *
 * <p>This repository extends {@link CrudRepository} (not {@code JpaRepository}) because
 * {@link CartState} is annotated with {@code @RedisHash("cart")} — it is a Redis hash
 * entity, not a JPA entity backed by a SQL database. Spring Data Redis auto-detects
 * and configures this repository via {@code @EnableRedisRepositories} declared in
 * the application's {@code RedisConfig}.
 *
 * <p>The generic type parameters are {@code <CartState, String>} because:
 * <ul>
 *   <li>{@code CartState} is the entity type (annotated with {@code @RedisHash("cart")})</li>
 *   <li>{@code String} is the ID type — the {@code id} field in {@code CartState}
 *       represents either a session ID (for anonymous users) or a username
 *       (for authenticated users)</li>
 * </ul>
 *
 * <p><strong>Inherited CRUD operations from {@link CrudRepository}:</strong>
 * <ul>
 *   <li>{@code save(CartState)} — persists or updates a cart state entry in Redis</li>
 *   <li>{@code findById(String)} — retrieves a cart by session ID or username</li>
 *   <li>{@code deleteById(String)} — removes a cart state entry (e.g., after checkout
 *       or session expiration)</li>
 *   <li>{@code existsById(String)} — checks whether a cart exists for the given ID</li>
 *   <li>{@code findAll()} — retrieves all stored cart states (administrative use)</li>
 *   <li>{@code count()} — returns the total number of active carts</li>
 *   <li>{@code deleteAll()} — removes all cart state entries (administrative use)</li>
 * </ul>
 *
 * <p><strong>No custom query methods are defined</strong> because all cart business
 * operations (add item, remove item, update quantity, compute subtotal, merge carts
 * on login) are handled by {@code CartStateService}, which reads the {@code CartState}
 * object, modifies it, and calls {@code save()} — a standard read-modify-write pattern
 * that maps directly to the inherited CRUD methods.
 *
 * <p><strong>Mapping from monolith Cart.java operations to repository calls:</strong>
 * <ul>
 *   <li>{@code Cart.containsItemId(itemId)} → {@code findById(id)}, then check items map
 *       — handled by {@code CartStateService}</li>
 *   <li>{@code Cart.addItem(item, isInStock)} → {@code findById(id)}, modify items map,
 *       {@code save()} — handled by {@code CartStateService}</li>
 *   <li>{@code Cart.removeItemById(itemId)} → {@code findById(id)}, remove from items map,
 *       {@code save()} — handled by {@code CartStateService}</li>
 *   <li>{@code Cart.setQuantityByItemId(itemId, qty)} → {@code findById(id)}, modify quantity,
 *       {@code save()} — handled by {@code CartStateService}</li>
 *   <li>{@code Cart.getSubTotal()} → {@code findById(id)}, compute from items
 *       — handled by {@code CartStateService}</li>
 *   <li>{@code Cart.getCartItemList()} → {@code findById(id)}, return items collection
 *       — handled by {@code CartStateService}</li>
 * </ul>
 *
 * @see CartState
 * @see CrudRepository
 */
@Repository
public interface CartStateRepository extends CrudRepository<CartState, String> {
    // All required CRUD operations (save, findById, deleteById, existsById,
    // findAll, count, deleteAll) are inherited from CrudRepository.
    // No custom query methods are needed — cart business logic resides in
    // CartStateService, which uses the inherited methods exclusively.
}
