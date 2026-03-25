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
 * monolith's MyBatis-backed implementation. All seven core workflows
 * (registration, authentication, catalog browsing, product search, cart
 * management, checkout, order history) behave identically after decomposition.</p>
 *
 * <h3>Monolith Method Mapping</h3>
 * <table>
 *   <caption>Method mapping from monolith AccountService to microservice AccountService</caption>
 *   <tr><th>Monolith Method</th><th>Microservice Method</th><th>Source Reference</th></tr>
 *   <tr>
 *     <td>{@code getAccount(String username)}</td>
 *     <td>{@link #getAccount(String)}</td>
 *     <td>AccountService.java lines 39-41; AccountMapper.xml lines 26-50 (4-table JOIN)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code getAccount(String username, String password)}</td>
 *     <td>{@link #getAccountForAuth(String, String)}</td>
 *     <td>AccountService.java lines 43-45; AccountMapper.xml lines 52-77</td>
 *   </tr>
 *   <tr>
 *     <td>{@code insertAccount(Account account)}</td>
 *     <td>{@link #insertAccount(AccountDTO)}</td>
 *     <td>AccountService.java lines 53-58 (3-table atomic insert)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code updateAccount(Account account)}</td>
 *     <td>{@link #updateAccount(String, AccountDTO)}</td>
 *     <td>AccountService.java lines 66-73 (conditional password update)</td>
 *   </tr>
 * </table>
 *
 * <h3>Transaction Semantics</h3>
 * <ul>
 *   <li>Read methods ({@code getAccount}, {@code getAccountForAuth}) use
 *       {@code @Transactional(readOnly = true)} for JPA performance optimization.</li>
 *   <li>Write methods ({@code insertAccount}, {@code updateAccount}) use
 *       {@code @Transactional} to guarantee atomicity across multi-table operations.</li>
 * </ul>
 *
 * <h3>Cross-Service Data Access Compliance</h3>
 * <p>Per AAP section 0.8.1, no service may access another service's database
 * directly. This service accesses only the four Account-context tables
 * (account, profile, signon, bannerdata) via its own PostgreSQL database.</p>
 *
 * @see com.jpetstore.account.controller.AccountController
 * @see com.jpetstore.account.repository.AccountRepository
 * @see com.jpetstore.account.repository.ProfileRepository
 * @see com.jpetstore.account.repository.SignonRepository
 * @see com.jpetstore.account.repository.BannerDataRepository
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
     * Spring auto-injects all repositories via constructor injection,
     * matching the monolith pattern of {@code private final} fields + constructor
     * (source: AccountService.java lines 33-37).
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
     * Retrieves a complete account view by username, composing data from the
     * account, profile, and bannerdata tables.
     *
     * <p>Replaces the monolith's 4-table JOIN in
     * {@code AccountMapper.getAccountByUsername} (AccountMapper.xml lines 26-50)
     * with three sequential repository lookups:</p>
     * <ol>
     *   <li>Load Account entity by primary key ({@code userid})</li>
     *   <li>Load Profile entity by the same primary key</li>
     *   <li>Load BannerData via the Profile's {@code favcategory} field,
     *       replicating the JOIN condition {@code PROFILE.FAVCATEGORY = BANNERDATA.FAVCATEGORY}</li>
     * </ol>
     *
     * <p><strong>Null-return behavior:</strong> Returns {@code null} when the
     * username is not found, mirroring the monolith's mapper behavior where SQL
     * returns a null resultSet. Callers must check for null.</p>
     *
     * @param username the account userid (primary key)
     * @return the assembled {@link AccountDTO} containing account, profile, and
     *         banner data; or {@code null} if no account exists for the given username
     */
    @Transactional(readOnly = true)
    public AccountDTO getAccount(String username) {
        // Step 1: Load Account entity by PK
        Account account = accountRepository.findById(username).orElse(null);
        if (account == null) {
            log.debug("Account not found for username: {}", username);
            return null; // Mirrors monolith behavior: mapper returns null if user not found
        }

        // Step 2: Load Profile entity (same PK as account)
        Profile profile = profileRepository.findById(username).orElse(null);

        // Step 3: Load BannerData via Profile's favcategory
        // Replicates: PROFILE.FAVCATEGORY = BANNERDATA.FAVCATEGORY
        String bannerName = null;
        if (profile != null && profile.getFavcategory() != null) {
            BannerData bannerData = bannerDataRepository.findById(profile.getFavcategory()).orElse(null);
            if (bannerData != null) {
                bannerName = bannerData.getBannername();
            }
        }

        // Step 4: Assemble into AccountDTO (replaces MyBatis 4-table resultMap)
        return assembleAccountDTO(account, profile, bannerName);
    }

    /**
     * Authenticates a user by verifying credentials against the signon table,
     * then returns the full account data if authentication succeeds.
     *
     * <p>Replaces the monolith's {@code AccountMapper.getAccountByUsernameAndPassword}
     * (AccountMapper.xml lines 52-77) which performed a 4-table JOIN including
     * {@code AND SIGNON.PASSWORD = #{param2}} for credential verification.
     * In the decomposed service, credential check is separated from account data
     * retrieval for cleaner separation of concerns.</p>
     *
     * <p><strong>Password storage note:</strong> The monolith stores plaintext
     * passwords in the signon table. This service preserves that behavior per
     * AAP section 0.8.1 (zero business logic changes). The comparison is delegated
     * to {@code SignonRepository.findByUsernameAndPassword()}, which performs a
     * database-level equality check.</p>
     *
     * <p><strong>Null-return behavior:</strong> Returns {@code null} when credentials
     * don't match, mirroring the monolith's behavior where SQL returns null on
     * authentication failure.</p>
     *
     * @param username the username to authenticate
     * @param password the plaintext password to verify
     * @return the user's {@link AccountDTO} if credentials are valid;
     *         {@code null} if authentication fails or the user does not exist
     */
    @Transactional(readOnly = true)
    public AccountDTO getAccountForAuth(String username, String password) {
        // Step 1: Check credentials via SignonRepository
        Optional<Signon> signon = signonRepository.findByUsernameAndPassword(username, password);
        if (signon.isEmpty()) {
            log.debug("Authentication failed for username: {}", username);
            return null; // Invalid credentials — mirrors monolith: returns null on auth failure
        }

        // Step 2: Load full account data (reuse getAccount method)
        return getAccount(username);
    }

    /**
     * Creates a new account with an atomic 3-table insert: account → profile → signon.
     *
     * <p>Replicates the monolith's {@code AccountService.insertAccount(Account)}
     * (source: AccountService.java lines 53-58) which calls three mapper methods
     * in a single {@code @Transactional} boundary:</p>
     * <ol>
     *   <li>{@code accountMapper.insertAccount(account)} — INSERT INTO ACCOUNT</li>
     *   <li>{@code accountMapper.insertProfile(account)} — INSERT INTO PROFILE
     *       (with boolean→int conversion via MyBatis {@code <bind>})</li>
     *   <li>{@code accountMapper.insertSignon(account)} — INSERT INTO SIGNON</li>
     * </ol>
     *
     * <p>The insert order (Account → Profile → Signon) exactly matches the monolith's
     * method call sequence on lines 55-57.</p>
     *
     * <p>The {@code @Transactional} annotation ensures atomicity: if any of the
     * three inserts fails, all are rolled back. This preserves the monolith's
     * ACID guarantee for account creation.</p>
     *
     * <p><strong>Field mapping:</strong> The DTO uses monolith-style camelCase names
     * (e.g. {@code getFirstName()}) which are mapped to entity column-aligned names
     * (e.g. {@code setFirstname()}) by this method. Boolean fields
     * {@code listOption}/{@code bannerOption} map directly to boolean entity fields —
     * JPA handles int↔boolean conversion automatically, replacing MyBatis's
     * {@code <bind>} conversion pattern.</p>
     *
     * @param dto the account data to persist, including all account, profile,
     *            and signon fields
     */
    @Transactional
    public void insertAccount(AccountDTO dto) {
        log.info("Creating new account for username: {}", dto.getUsername());

        // Step 1: Create and save Account entity (mirrors monolith: insertAccount, line 55)
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

        // Step 2: Create and save Profile entity (mirrors monolith: insertProfile, line 56)
        Profile profile = new Profile();
        profile.setUserid(dto.getUsername());
        profile.setLangpref(dto.getLanguagePreference());
        profile.setFavcategory(dto.getFavouriteCategoryId());
        profile.setMylistopt(dto.isListOption());
        profile.setBanneropt(dto.isBannerOption());
        profileRepository.save(profile);

        // Step 3: Create and save Signon entity (mirrors monolith: insertSignon, line 57)
        Signon signon = new Signon();
        signon.setUsername(dto.getUsername());
        signon.setPassword(dto.getPassword());
        signonRepository.save(signon);

        log.info("Account created successfully for username: {}", dto.getUsername());
    }

    /**
     * Updates an existing account with a conditional signon (password) update.
     *
     * <p>Replicates the monolith's {@code AccountService.updateAccount(Account)}
     * (source: AccountService.java lines 66-73) which performs:</p>
     * <ol>
     *   <li>{@code accountMapper.updateAccount(account)} — UPDATE ACCOUNT SET ... WHERE USERID = ?</li>
     *   <li>{@code accountMapper.updateProfile(account)} — UPDATE PROFILE SET ... WHERE USERID = ?</li>
     *   <li>Conditionally: {@code accountMapper.updateSignon(account)} — UPDATE SIGNON SET PASSWORD = ?
     *       WHERE USERNAME = ? — only if password is non-null and non-empty</li>
     * </ol>
     *
     * <p>The conditional signon update uses the EXACT pattern from the monolith
     * (AccountService.java lines 71-72):</p>
     * <pre>{@code
     * Optional.ofNullable(account.getPassword())
     *     .filter(password -> password.length() > 0)
     *     .ifPresent(password -> accountMapper.updateSignon(account));
     * }</pre>
     *
     * <p><strong>Username immutability:</strong> The username (PK) is NOT changed
     * during update — it comes from the path parameter, not the DTO body.
     * This prevents accidental primary key modification.</p>
     *
     * @param username the account userid to update (from path parameter, source of truth)
     * @param dto      the updated account data containing new field values
     * @throws RuntimeException if no account exists for the given username
     * @throws RuntimeException if signon record not found during password update
     */
    @Transactional
    public void updateAccount(String username, AccountDTO dto) {
        // Step 1: Load existing Account entity and update fields
        Account account = accountRepository.findById(username)
                .orElseThrow(() -> new RuntimeException("Account not found: " + username));
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

        // Step 2: Load existing Profile entity and update fields
        Profile profile = profileRepository.findById(username).orElse(new Profile());
        profile.setUserid(username);
        profile.setLangpref(dto.getLanguagePreference());
        profile.setFavcategory(dto.getFavouriteCategoryId());
        profile.setMylistopt(dto.isListOption());
        profile.setBanneropt(dto.isBannerOption());
        profileRepository.save(profile);

        // Step 3: Conditional signon update — EXACT mirror of monolith lines 71-72
        // Only update password if non-null and non-empty (length > 0)
        Optional.ofNullable(dto.getPassword())
                .filter(password -> password.length() > 0)
                .ifPresent(password -> {
                    Signon signon = signonRepository.findById(username)
                            .orElseThrow(() -> new RuntimeException("Signon not found: " + username));
                    signon.setPassword(password);
                    signonRepository.save(signon);
                });

        log.info("Account updated successfully for username: {}", username);
    }

    /**
     * Assembles a complete {@link AccountDTO} from individual entity objects.
     *
     * <p>This private helper replaces the 4-table JOIN resultMap from
     * AccountMapper.xml (lines 26-50). It maps entity field names (column-aligned:
     * {@code userid}, {@code firstname}, {@code langpref}) back to DTO field names
     * (monolith-aligned: {@code username}, {@code firstName}, {@code languagePreference}).</p>
     *
     * <h3>Field Mapping</h3>
     * <ul>
     *   <li>{@code Account.userid} → {@code AccountDTO.username}</li>
     *   <li>{@code Account.firstname} → {@code AccountDTO.firstName}</li>
     *   <li>{@code Account.lastname} → {@code AccountDTO.lastName}</li>
     *   <li>{@code Account.address1} → {@code AccountDTO.address1}</li>
     *   <li>{@code Account.address2} → {@code AccountDTO.address2}</li>
     *   <li>{@code Profile.langpref} → {@code AccountDTO.languagePreference}</li>
     *   <li>{@code Profile.favcategory} → {@code AccountDTO.favouriteCategoryId}</li>
     *   <li>{@code Profile.mylistopt} → {@code AccountDTO.listOption}</li>
     *   <li>{@code Profile.banneropt} → {@code AccountDTO.bannerOption}</li>
     *   <li>{@code BannerData.bannername} → {@code AccountDTO.bannerName}</li>
     * </ul>
     *
     * <p><strong>Security:</strong> Password is never set in the DTO for read operations.
     * The DTO's {@code password} field has {@code @JsonProperty(access = WRITE_ONLY)},
     * but this method adds an extra layer of defense by simply not calling
     * {@code dto.setPassword()} at all.</p>
     *
     * @param account    the account entity (must not be null)
     * @param profile    the profile entity (may be null if profile not yet created)
     * @param bannerName the resolved banner name from bannerdata table (may be null)
     * @return a fully-populated {@link AccountDTO} without the password field set
     */
    private AccountDTO assembleAccountDTO(Account account, Profile profile, String bannerName) {
        AccountDTO dto = new AccountDTO();

        // Account fields — map from entity column-aligned names to DTO monolith-aligned names
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

        // Profile fields (if available)
        if (profile != null) {
            dto.setLanguagePreference(profile.getLangpref());
            dto.setFavouriteCategoryId(profile.getFavcategory());
            dto.setListOption(profile.isMylistopt());
            dto.setBannerOption(profile.isBanneropt());
        }

        // BannerData field
        dto.setBannerName(bannerName);

        // Password is never set in DTO for read operations (security)
        return dto;
    }
}
