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
package com.jpetstore.account.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jpetstore.account.dto.AccountDTO;
import com.jpetstore.account.entity.Account;
import com.jpetstore.account.entity.BannerData;
import com.jpetstore.account.entity.Profile;
import com.jpetstore.account.entity.Signon;
import com.jpetstore.account.repository.AccountRepository;
import com.jpetstore.account.repository.BannerDataRepository;
import com.jpetstore.account.repository.ProfileRepository;
import com.jpetstore.account.repository.SignonRepository;

/**
 * Business logic service for the Account bounded context, replicating the
 * monolith's {@code org.mybatis.jpetstore.service.AccountService} behavior
 * using Spring Data JPA repositories against a PostgreSQL database.
 *
 * <p>This service manages the four account-context tables (account, profile,
 * signon, bannerdata) and provides the same transactional guarantees as the
 * monolith's MyBatis-backed implementation.</p>
 *
 * <h3>Monolith Method Mapping</h3>
 * <ul>
 *   <li>{@link #getAccount(String)} — replaces {@code AccountService.getAccount(String, String)}
 *       with the 4-table JOIN decomposed into sequential repository lookups</li>
 *   <li>{@link #getAccountForAuth(String, String)} — replaces the credential check from
 *       {@code AccountService.getAccount(String, String)} (AccountMapper.getAccountByUsernameAndPassword)</li>
 *   <li>{@link #insertAccount(AccountDTO)} — replaces the 3-table insert:
 *       {@code accountMapper.insertAccount + insertProfile + insertSignon}
 *       (source: AccountService.java lines 54-57)</li>
 *   <li>{@link #updateAccount(String, AccountDTO)} — replaces the conditional update:
 *       {@code accountMapper.updateAccount + updateProfile + optional updateSignon}
 *       (source: AccountService.java lines 63-72)</li>
 * </ul>
 *
 * @see com.jpetstore.account.controller.AccountController
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final ProfileRepository profileRepository;
    private final SignonRepository signonRepository;
    private final BannerDataRepository bannerDataRepository;

    /**
     * Constructs the AccountService with all four repository dependencies.
     *
     * @param accountRepository    repository for the account table
     * @param profileRepository    repository for the profile table
     * @param signonRepository     repository for the signon table
     * @param bannerDataRepository repository for the bannerdata table
     */
    public AccountService(AccountRepository accountRepository,
                          ProfileRepository profileRepository,
                          SignonRepository signonRepository,
                          BannerDataRepository bannerDataRepository) {
        this.accountRepository = accountRepository;
        this.profileRepository = profileRepository;
        this.signonRepository = signonRepository;
        this.bannerDataRepository = bannerDataRepository;
    }

    /**
     * Retrieves a complete account view by username, composing data from
     * account, profile, and bannerdata tables.
     *
     * <p>Replaces the monolith's 4-table JOIN in {@code AccountMapper.getAccountByUsername}
     * with sequential repository lookups. The result includes all account fields,
     * profile preferences, and the resolved banner name.</p>
     *
     * @param username the account userid (primary key)
     * @return an {@link Optional} containing the assembled {@link AccountDTO},
     *         or empty if no account exists for the given username
     */
    @Transactional(readOnly = true)
    public Optional<AccountDTO> getAccount(String username) {
        Optional<Account> accountOpt = accountRepository.findById(username);
        if (accountOpt.isEmpty()) {
            log.debug("Account not found for username: {}", username);
            return Optional.empty();
        }

        Account account = accountOpt.get();
        Profile profile = profileRepository.findById(username).orElse(null);

        // Resolve banner name from bannerdata table via the user's favourite category
        String bannerName = null;
        if (profile != null && profile.getFavcategory() != null) {
            bannerName = bannerDataRepository.findByFavcategory(profile.getFavcategory())
                    .map(BannerData::getBannername)
                    .orElse(null);
        }

        return Optional.of(assembleAccountDTO(account, profile, bannerName));
    }

    /**
     * Authenticates a user by verifying credentials against the signon table.
     *
     * <p>Replaces the monolith's {@code AccountMapper.getAccountByUsernameAndPassword}
     * which performed a 4-table JOIN including the signon table for credential
     * verification. In the decomposed service, credential check is separated from
     * account data retrieval for cleaner separation of concerns.</p>
     *
     * <p><strong>Password storage note:</strong> The monolith stores plaintext
     * passwords in the signon table. This service preserves that behavior during
     * the dual-write coexistence window. The comparison is delegated to
     * {@code SignonRepository.findByUsernameAndPassword()}, which performs a
     * database-level equality check.</p>
     *
     * @param username the username to authenticate
     * @param password the plaintext password to verify
     * @return an {@link Optional} containing the user's {@link AccountDTO} if
     *         credentials are valid, or empty if authentication fails
     */
    @Transactional(readOnly = true)
    public Optional<AccountDTO> getAccountForAuth(String username, String password) {
        Optional<Signon> signonOpt = signonRepository.findByUsernameAndPassword(username, password);
        if (signonOpt.isEmpty()) {
            log.debug("Authentication failed for username: {}", username);
            return Optional.empty();
        }
        return getAccount(username);
    }

    /**
     * Creates a new account with atomic 3-table insert: account + profile + signon.
     *
     * <p>Replicates the monolith's {@code AccountService.insertAccount(Account)} which
     * calls three mapper methods in a single {@code @Transactional} boundary:</p>
     * <ol>
     *   <li>{@code accountMapper.insertAccount(account)} — insert into account table</li>
     *   <li>{@code accountMapper.insertProfile(account)} — insert into profile table</li>
     *   <li>{@code accountMapper.insertSignon(account)} — insert into signon table</li>
     * </ol>
     * <p>(source: AccountService.java lines 54-57)</p>
     *
     * <p>The {@code @Transactional} annotation ensures atomicity: if any of the
     * three inserts fails, all are rolled back. This preserves the monolith's
     * ACID guarantee for account creation.</p>
     *
     * @param dto the account data to persist, including all account, profile,
     *            and signon fields
     * @return the created {@link AccountDTO} with all fields populated
     */
    @Transactional
    public AccountDTO insertAccount(AccountDTO dto) {
        log.info("Creating new account for username: {}", dto.getUsername());

        // Step 1: Insert into account table
        Account account = new Account();
        account.setUserid(dto.getUsername());
        account.setEmail(dto.getEmail());
        account.setFirstname(dto.getFirstName());
        account.setLastname(dto.getLastName());
        account.setStatus(dto.getStatus());
        account.setAddress1(dto.getAddress1());
        account.setAddress2(dto.getAddress2());
        account.setCity(dto.getCity());
        account.setState(dto.getState());
        account.setZip(dto.getZip());
        account.setCountry(dto.getCountry());
        account.setPhone(dto.getPhone());
        accountRepository.save(account);

        // Step 2: Insert into profile table
        Profile profile = new Profile();
        profile.setUserid(dto.getUsername());
        profile.setLangpref(dto.getLanguagePreference());
        profile.setFavcategory(dto.getFavouriteCategoryId());
        profile.setMylistopt(dto.isListOption());
        profile.setBanneropt(dto.isBannerOption());
        profileRepository.save(profile);

        // Step 3: Insert into signon table (plaintext password per monolith behavior)
        Signon signon = new Signon(dto.getUsername(), dto.getPassword());
        signonRepository.save(signon);

        // Resolve banner name for the response
        String bannerName = null;
        if (dto.getFavouriteCategoryId() != null) {
            bannerName = bannerDataRepository.findByFavcategory(dto.getFavouriteCategoryId())
                    .map(BannerData::getBannername)
                    .orElse(null);
        }

        log.info("Account created successfully for username: {}", dto.getUsername());
        return assembleAccountDTO(account, profile, bannerName);
    }

    /**
     * Updates an existing account with conditional signon (password) update.
     *
     * <p>Replicates the monolith's {@code AccountService.updateAccount(Account)}
     * which calls:</p>
     * <ol>
     *   <li>{@code accountMapper.updateAccount(account)} — update account table</li>
     *   <li>{@code accountMapper.updateProfile(account)} — update profile table</li>
     *   <li>Conditionally: {@code accountMapper.updateSignon(account)} — update signon
     *       table only if password is non-null and non-empty</li>
     * </ol>
     *
     * <p>The conditional signon update uses the exact pattern from the monolith
     * (AccountService.java lines 71-72):</p>
     * <pre>{@code
     * Optional.ofNullable(account.getPassword())
     *     .filter(password -> password.length() > 0)
     *     .ifPresent(password -> accountMapper.updateSignon(account));
     * }</pre>
     *
     * @param username the account userid to update (path variable, source of truth)
     * @param dto      the updated account data
     * @return an {@link Optional} containing the updated {@link AccountDTO},
     *         or empty if no account exists for the given username
     */
    @Transactional
    public Optional<AccountDTO> updateAccount(String username, AccountDTO dto) {
        Optional<Account> existingOpt = accountRepository.findById(username);
        if (existingOpt.isEmpty()) {
            log.debug("Cannot update — account not found for username: {}", username);
            return Optional.empty();
        }

        log.info("Updating account for username: {}", username);

        // Step 1: Update account table
        Account account = existingOpt.get();
        account.setEmail(dto.getEmail());
        account.setFirstname(dto.getFirstName());
        account.setLastname(dto.getLastName());
        account.setStatus(dto.getStatus());
        account.setAddress1(dto.getAddress1());
        account.setAddress2(dto.getAddress2());
        account.setCity(dto.getCity());
        account.setState(dto.getState());
        account.setZip(dto.getZip());
        account.setCountry(dto.getCountry());
        account.setPhone(dto.getPhone());
        accountRepository.save(account);

        // Step 2: Update profile table
        Profile profile = profileRepository.findById(username).orElseGet(() -> {
            Profile p = new Profile();
            p.setUserid(username);
            return p;
        });
        profile.setLangpref(dto.getLanguagePreference());
        profile.setFavcategory(dto.getFavouriteCategoryId());
        profile.setMylistopt(dto.isListOption());
        profile.setBanneropt(dto.isBannerOption());
        profileRepository.save(profile);

        // Step 3: Conditional signon update — only if password is non-null and non-empty
        // Mirrors monolith pattern: Optional.ofNullable(account.getPassword())
        //     .filter(password -> password.length() > 0)
        //     .ifPresent(password -> accountMapper.updateSignon(account));
        Optional.ofNullable(dto.getPassword())
                .filter(password -> password.length() > 0)
                .ifPresent(password -> {
                    Signon signon = signonRepository.findById(username)
                            .orElseGet(() -> new Signon(username, null));
                    signon.setPassword(password);
                    signonRepository.save(signon);
                });

        // Resolve banner name for the response
        String bannerName = null;
        if (dto.getFavouriteCategoryId() != null) {
            bannerName = bannerDataRepository.findByFavcategory(dto.getFavouriteCategoryId())
                    .map(BannerData::getBannername)
                    .orElse(null);
        }

        log.info("Account updated successfully for username: {}", username);
        return Optional.of(assembleAccountDTO(account, profile, bannerName));
    }

    /**
     * Assembles a complete {@link AccountDTO} from individual entity objects.
     *
     * <p>Maps entity field names to DTO field names per the decomposition mapping:</p>
     * <ul>
     *   <li>{@code Account.userid} → {@code AccountDTO.username}</li>
     *   <li>{@code Account.firstname} → {@code AccountDTO.firstName}</li>
     *   <li>{@code Account.lastname} → {@code AccountDTO.lastName}</li>
     *   <li>{@code Account.address1} → {@code AccountDTO.address1}</li>
     *   <li>{@code Profile.langpref} → {@code AccountDTO.languagePreference}</li>
     *   <li>{@code Profile.favcategory} → {@code AccountDTO.favouriteCategoryId}</li>
     *   <li>{@code Profile.mylistopt} → {@code AccountDTO.listOption}</li>
     *   <li>{@code Profile.banneropt} → {@code AccountDTO.bannerOption}</li>
     *   <li>{@code BannerData.bannername} → {@code AccountDTO.bannerName}</li>
     * </ul>
     *
     * @param account    the account entity (must not be null)
     * @param profile    the profile entity (may be null if not yet created)
     * @param bannerName the resolved banner name (may be null)
     * @return a fully-populated {@link AccountDTO}
     */
    private AccountDTO assembleAccountDTO(Account account, Profile profile, String bannerName) {
        AccountDTO dto = new AccountDTO();
        dto.setUsername(account.getUserid());
        dto.setEmail(account.getEmail());
        dto.setFirstName(account.getFirstname());
        dto.setLastName(account.getLastname());
        dto.setStatus(account.getStatus());
        dto.setAddress1(account.getAddress1());
        dto.setAddress2(account.getAddress2());
        dto.setCity(account.getCity());
        dto.setState(account.getState());
        dto.setZip(account.getZip());
        dto.setCountry(account.getCountry());
        dto.setPhone(account.getPhone());

        if (profile != null) {
            dto.setLanguagePreference(profile.getLangpref());
            dto.setFavouriteCategoryId(profile.getFavcategory());
            dto.setListOption(profile.isMylistopt());
            dto.setBannerOption(profile.isBanneropt());
        }

        if (bannerName != null) {
            dto.setBannerName(bannerName);
        }

        return dto;
    }
}
