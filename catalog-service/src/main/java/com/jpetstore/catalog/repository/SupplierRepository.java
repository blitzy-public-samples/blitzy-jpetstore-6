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

import com.jpetstore.catalog.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Supplier} entity in the Catalog Service.
 *
 * <p>This repository provides standard CRUD operations for the {@code supplier} table
 * in the Catalog Service's PostgreSQL database. In the original JPetStore monolith,
 * there was no dedicated {@code SupplierMapper} — the supplier table was only accessed
 * implicitly through foreign key JOINs from the {@code item} table
 * ({@code item.supplier → supplier.suppid}). In the decomposed microservices
 * architecture, Supplier is promoted to a first-class entity with its own repository,
 * enabling direct CRUD management of supplier records.</p>
 *
 * <h3>Inherited Operations from {@link JpaRepository}</h3>
 * <ul>
 *   <li>{@code findAll()} — retrieves all suppliers from the supplier table</li>
 *   <li>{@code findById(Integer id)} — retrieves a supplier by its primary key ({@code suppid})</li>
 *   <li>{@code save(Supplier entity)} — inserts a new supplier or updates an existing one</li>
 *   <li>{@code deleteById(Integer id)} — deletes a supplier by its primary key</li>
 * </ul>
 *
 * <p>No custom query methods are declared because all required data access patterns
 * are fully satisfied by the built-in {@link JpaRepository} methods. Spring Data JPA
 * auto-detects this interface at application startup and generates the repository
 * implementation at runtime — no {@code @Repository} annotation is needed.</p>
 *
 * <h3>Entity Mapping</h3>
 * <p>The generic type parameters are {@code <Supplier, Integer>} because the
 * {@link Supplier} entity's primary key field ({@code suppId}) is of type
 * {@link Integer}, derived from the database schema:
 * {@code suppid int not null} with {@code constraint pk_supplier primary key (suppid)}.</p>
 *
 * @see Supplier
 * @see JpaRepository
 */
public interface SupplierRepository extends JpaRepository<Supplier, Integer> {
    // No custom query methods required.
    // All CRUD operations (findAll, findById, save, deleteById) are provided
    // by the JpaRepository base interface. Spring Data JPA generates the
    // implementation at runtime.
}
