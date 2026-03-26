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
package com.jpetstore.account.repository;

import com.jpetstore.account.entity.Account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} entity.
 *
 * <p>This repository replaces the account-table operations from the monolith's
 * MyBatis {@code AccountMapper} interface. All required CRUD operations are
 * provided by the inherited {@link JpaRepository} methods — no custom query
 * methods are needed.</p>
 *
 * <h3>Mapping from Monolith AccountMapper to JpaRepository Methods</h3>
 * <table>
 *   <caption>AccountMapper method to JpaRepository method mapping</caption>
 *   <tr><th>Monolith Method</th><th>Repository Method</th><th>Notes</th></tr>
 *   <tr>
 *     <td>{@code getAccountByUsername(String)}</td>
 *     <td>{@link #findById(Object) findById(String)}</td>
 *     <td>{@code userid} is the primary key — standard {@code findById()} suffices</td>
 *   </tr>
 *   <tr>
 *     <td>{@code insertAccount(Account)}</td>
 *     <td>{@link #save(Object) save(Account)}</td>
 *     <td>JPA {@code save()} performs INSERT for new (transient) entities</td>
 *   </tr>
 *   <tr>
 *     <td>{@code updateAccount(Account)}</td>
 *     <td>{@link #save(Object) save(Account)}</td>
 *     <td>JPA {@code save()} performs UPDATE for managed (persistent) entities</td>
 *   </tr>
 * </table>
 *
 * <p>The monolith's multi-table JOIN queries (combining account, profile, signon,
 * and bannerdata) are decomposed in the new microservice architecture: each table
 * has its own repository ({@code AccountRepository}, {@code ProfileRepository},
 * {@code SignonRepository}, {@code BannerDataRepository}), and the service layer
 * coordinates the individual lookups.</p>
 *
 * <p>Additional inherited methods available from {@link JpaRepository}:</p>
 * <ul>
 *   <li>{@link #findAll()} — retrieves all account records</li>
 *   <li>{@link #deleteById(Object)} — deletes an account by userid</li>
 *   <li>{@link #existsById(Object)} — checks if an account exists by userid</li>
 *   <li>{@link #count()} — returns the total number of account records</li>
 * </ul>
 *
 * <p>The {@code @Repository} annotation marks this interface as a Spring-managed
 * data-access component, enabling automatic translation of JPA/JDBC exceptions
 * into Spring's {@link org.springframework.dao.DataAccessException} hierarchy.</p>
 *
 * @see Account
 * @see JpaRepository
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    // All required CRUD operations are inherited from JpaRepository:
    //
    //   save(Account)       — INSERT or UPDATE an account
    //   findById(String)    — SELECT account by userid (primary key)
    //   findAll()           — SELECT all accounts
    //   deleteById(String)  — DELETE account by userid
    //   existsById(String)  — check existence by userid
    //   count()             — count all account records
    //
    // No custom query methods are needed for this repository.
}
