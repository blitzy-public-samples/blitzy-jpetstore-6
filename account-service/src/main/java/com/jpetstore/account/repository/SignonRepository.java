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

import java.util.Optional;

import com.jpetstore.account.entity.Signon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@code signon} table in the Account Service's
 * PostgreSQL database.
 *
 * <p>This repository replaces the signon-related methods from the monolith's MyBatis
 * {@code AccountMapper} interface. The original mapper combined four tables
 * ({@code account}, {@code profile}, {@code signon}, {@code bannerdata}) in a single
 * query for authentication. In the decomposed microservice architecture, the signon
 * check is performed independently against only the {@code signon} table, and the
 * application service layer coordinates any cross-entity operations.</p>
 *
 * <h3>Monolith AccountMapper Method Mapping</h3>
 * <table>
 *   <tr><th>Monolith Method</th><th>Repository Method</th><th>Notes</th></tr>
 *   <tr>
 *     <td>{@code getAccountByUsernameAndPassword(username, password)}</td>
 *     <td>{@link #findByUsernameAndPassword(String, String)}</td>
 *     <td>Custom derived query — credential verification</td>
 *   </tr>
 *   <tr>
 *     <td>{@code insertSignon(Account)}</td>
 *     <td>{@link #save(Object)} (inherited)</td>
 *     <td>JPA merge/persist handles INSERT for new entities</td>
 *   </tr>
 *   <tr>
 *     <td>{@code updateSignon(Account)}</td>
 *     <td>{@link #save(Object)} (inherited)</td>
 *     <td>JPA merge handles UPDATE for managed/existing entities</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Security note:</strong> This repository stores passwords in plain text,
 * matching the monolith's current behavior. The AAP mandates zero business logic
 * changes — password hashing would constitute a behavior change and is out of scope
 * for this decomposition.</p>
 *
 * @see Signon
 * @see JpaRepository
 */
@Repository
public interface SignonRepository extends JpaRepository<Signon, String> {

    /**
     * Finds a signon record matching both the given username and password.
     *
     * <p>Spring Data JPA derives the query automatically from the method name:</p>
     * <pre>
     * SELECT s FROM Signon s WHERE s.username = ?1 AND s.password = ?2
     * </pre>
     *
     * <p>This replaces the authentication portion of the monolith's
     * {@code AccountMapper.getAccountByUsernameAndPassword()} which performed a 4-table
     * JOIN with the conditions:</p>
     * <pre>
     * WHERE ACCOUNT.USERID = #{param1}
     *   AND SIGNON.PASSWORD = #{param2}
     *   AND SIGNON.USERNAME = ACCOUNT.USERID
     * </pre>
     *
     * <p>Returns {@link Optional#empty()} when no signon record matches the provided
     * credentials, enabling null-safe credential verification in the service layer.</p>
     *
     * @param username the username to match against the {@code username} column (primary key)
     * @param password the password to match against the {@code password} column
     * @return an {@link Optional} containing the matching {@link Signon} entity,
     *         or {@link Optional#empty()} if no record matches both criteria
     */
    Optional<Signon> findByUsernameAndPassword(String username, String password);
}
