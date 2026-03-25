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

import com.jpetstore.account.entity.BannerData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@code bannerdata} table in the Account
 * Service's PostgreSQL database.
 *
 * <p>This repository replaces the banner-data portion of the monolith's
 * {@code AccountMapper.getAccountByUsername()} four-table JOIN query.  In the
 * original monolith the banner name is resolved via:</p>
 * <pre>{@code
 *   SELECT ... BANNERDATA.BANNERNAME
 *   FROM ACCOUNT, PROFILE, SIGNON, BANNERDATA
 *   WHERE ...
 *     AND PROFILE.FAVCATEGORY = BANNERDATA.FAVCATEGORY
 * }</pre>
 *
 * <p>In the decomposed Account Service the same lookup is performed in two
 * sequential steps by the service layer:</p>
 * <ol>
 *   <li>{@code profileRepository.findById(userid)} &rarr; obtains the
 *       user's {@code favcategory} value.</li>
 *   <li>{@code bannerDataRepository.findByFavcategory(favcategory)} (or the
 *       equivalent {@code findById(favcategory)}) &rarr; resolves the
 *       {@code bannername}.</li>
 * </ol>
 *
 * <p>The {@code bannerdata} table is <strong>read-only</strong> in the
 * monolith — no INSERT, UPDATE, or DELETE operations are ever performed on it.
 * The new service honours this pattern; however, {@link JpaRepository}
 * provides write methods by default and no restriction is applied at the
 * repository level so that data-seeding and migration tooling can still
 * operate normally.</p>
 *
 * <h3>Inherited CRUD Operations (from {@link JpaRepository})</h3>
 * <ul>
 *   <li>{@code findById(String favcategory)} — look up banner data by primary key</li>
 *   <li>{@code save(BannerData entity)} — persist or merge a banner data record</li>
 *   <li>{@code findAll()} — retrieve every banner data record</li>
 *   <li>{@code deleteById(String favcategory)} — remove a record by primary key</li>
 *   <li>{@code existsById(String favcategory)} — check existence by primary key</li>
 *   <li>{@code count()} — total number of banner data records</li>
 * </ul>
 *
 * @author Blitzy Platform
 * @see com.jpetstore.account.entity.BannerData
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface BannerDataRepository extends JpaRepository<BannerData, String> {

    /**
     * Finds the {@link BannerData} record for the given favourite category
     * identifier.
     *
     * <p>Because {@code favcategory} is the primary key of the
     * {@code bannerdata} table, this derived query method is functionally
     * equivalent to {@code findById(String)}.  It is declared explicitly to
     * make the repository API self-documenting — callers can see at a glance
     * that the lookup is by favourite-category rather than by an opaque
     * identifier.</p>
     *
     * <p>Spring Data JPA derives the query automatically from the method
     * name, producing SQL equivalent to:</p>
     * <pre>{@code
     *   SELECT * FROM bannerdata WHERE favcategory = ?
     * }</pre>
     *
     * @param favcategory the favourite category identifier to search for,
     *                    e.g. {@code "FISH"}, {@code "DOGS"}, {@code "CATS"}
     * @return an {@link Optional} containing the matching {@link BannerData}
     *         if found, or {@link Optional#empty()} if no banner data exists
     *         for the given category
     */
    Optional<BannerData> findByFavcategory(String favcategory);
}
