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

import com.jpetstore.account.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Profile} entity, providing CRUD operations
 * against the {@code profile} PostgreSQL table in the Account Service microservice.
 *
 * <p>This repository replaces the profile-related methods from the monolith's MyBatis
 * {@code AccountMapper} interface. All required data access operations are provided by
 * the inherited {@link JpaRepository} methods — no custom query methods are needed.
 *
 * <h3>Monolith Migration Mapping</h3>
 * <table>
 *   <caption>AccountMapper → ProfileRepository method mapping</caption>
 *   <tr><th>Monolith AccountMapper Method</th><th>ProfileRepository Method (inherited)</th></tr>
 *   <tr>
 *     <td>{@code insertProfile(Account)} — inserts a new profile row with langpref,
 *         favcategory, mylistopt, banneropt, userid</td>
 *     <td>{@code save(Profile)} — the service layer constructs a {@link Profile} entity
 *         from account data and delegates to this inherited method</td>
 *   </tr>
 *   <tr>
 *     <td>{@code updateProfile(Account)} — updates langpref, favcategory, mylistopt,
 *         banneropt for a given userid</td>
 *     <td>{@code save(Profile)} — JPA merge semantics handle both insert and update;
 *         the service layer maps the account data to a {@link Profile} entity</td>
 *   </tr>
 *   <tr>
 *     <td>Profile columns from {@code getAccountByUsername(String)} — reads langpref,
 *         favcategory, mylistopt, banneropt via a multi-table JOIN</td>
 *     <td>{@code findById(String userid)} — returns {@code Optional<Profile>} with all
 *         profile fields; the service layer composes the full account view</td>
 *   </tr>
 * </table>
 *
 * <h3>Boolean ↔ Integer Conversion</h3>
 * <p>The monolith's AccountMapper.xml used MyBatis {@code <bind>} expressions to convert
 * between Java {@code boolean} and SQL {@code int} for the {@code mylistopt} and
 * {@code banneropt} columns. In the microservice architecture, JPA/Hibernate handles this
 * conversion automatically through its {@code BasicTypeRegistry}, so no special repository
 * or converter configuration is required.
 *
 * <h3>Inherited Operations</h3>
 * <p>The following standard CRUD operations are available via {@link JpaRepository}:
 * <ul>
 *   <li>{@code save(Profile)} — persist or merge a profile entity</li>
 *   <li>{@code findById(String)} — find a profile by userid, returning {@code Optional<Profile>}</li>
 *   <li>{@code findAll()} — retrieve all profile records</li>
 *   <li>{@code deleteById(String)} — delete a profile by userid</li>
 *   <li>{@code existsById(String)} — check if a profile exists for the given userid</li>
 *   <li>{@code count()} — count the total number of profile records</li>
 * </ul>
 *
 * <p>Spring Data JPA auto-generates the repository implementation at runtime via
 * proxy-based infrastructure — no concrete implementation class is needed.
 *
 * @see Profile
 * @see JpaRepository
 */
@Repository
public interface ProfileRepository extends JpaRepository<Profile, String> {
    // All required operations (save, findById, findAll, deleteById, existsById, count)
    // are inherited from JpaRepository. No custom query methods are needed for the
    // profile table operations extracted from the monolith's AccountMapper.
}
