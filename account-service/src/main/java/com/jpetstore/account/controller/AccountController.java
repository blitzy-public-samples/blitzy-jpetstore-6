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
package com.jpetstore.account.controller;

import java.util.Optional;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jpetstore.account.dto.AccountDTO;
import com.jpetstore.account.dto.SignonRequest;
import com.jpetstore.account.dto.SignonResponse;
import com.jpetstore.account.security.JwtTokenProvider;
import com.jpetstore.account.security.LoginAttemptService;
import com.jpetstore.account.service.AccountService;

/**
 * REST controller for the Account Service microservice, exposing account
 * management and authentication endpoints.
 *
 * <p>This controller replaces the monolith's Stripes-based
 * {@code AccountActionBean} with a stateless REST API. Each endpoint maps
 * directly to a monolith ActionBean handler method:</p>
 *
 * <table>
 *   <caption>Monolith → Microservice endpoint mapping</caption>
 *   <tr><th>Monolith ActionBean Method</th><th>REST Endpoint</th></tr>
 *   <tr>
 *     <td>{@code AccountActionBean.signon()} (lines 159-177)</td>
 *     <td>{@code POST /api/accounts/signon}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AccountActionBean.newAccount()} (lines 115-121)</td>
 *     <td>{@code POST /api/accounts}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AccountActionBean.editAccount()} (lines 134-140)</td>
 *     <td>{@code PUT /api/accounts/{username}}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AccountActionBean.editAccountForm()} — account retrieval</td>
 *     <td>{@code GET /api/accounts/{username}}</td>
 *   </tr>
 * </table>
 *
 * <h3>Authentication Model</h3>
 * <p>The monolith used {@code @SessionScope} on the ActionBean with an
 * {@code authenticated} boolean flag. The microservice replaces this with
 * JWT tokens: the signon endpoint issues a token, and the API Gateway plus
 * the service's security filter validate it on subsequent requests.</p>
 *
 * <h3>Authorization Rules</h3>
 * <ul>
 *   <li>{@code POST /signon} — public (no auth required)</li>
 *   <li>{@code POST /} — public (registration, no existing token)</li>
 *   <li>{@code GET /{username}} — authenticated (JWT required)</li>
 *   <li>{@code PUT /{username}} — authenticated (JWT required)</li>
 * </ul>
 *
 * @see com.jpetstore.account.service.AccountService
 * @see com.jpetstore.account.security.JwtTokenProvider
 * @see com.jpetstore.account.security.SecurityConfig
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;

    /**
     * Constructs the AccountController with required dependencies.
     *
     * @param accountService     the account business logic service
     * @param jwtTokenProvider   the JWT token generation and validation provider
     * @param loginAttemptService the brute-force login attempt tracking service
     */
    public AccountController(AccountService accountService, JwtTokenProvider jwtTokenProvider,
                             LoginAttemptService loginAttemptService) {
        this.accountService = accountService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * Authenticates a user and returns a JWT token.
     *
     * <p>Replaces the monolith's {@code AccountActionBean.signon()} method
     * (lines 159-177). In the monolith, successful authentication sets
     * {@code authenticated = true} on the session-scoped ActionBean and
     * stores the account in the HTTP session. In the microservice, this is
     * replaced by a stateless JWT token containing the username claim.</p>
     *
     * <p>On failure, returns 401 Unauthorized, mirroring the monolith's
     * error message "Invalid username or password. Signon failed." when
     * {@code accountService.getAccount(username, password)} returns null
     * (AccountActionBean.java line 163).</p>
     *
     * @param request the signon credentials (username and password)
     * @return 200 OK with {@link SignonResponse} containing JWT token, or
     *         401 Unauthorized if credentials are invalid
     */
    @PostMapping("/signon")
    public ResponseEntity<?> signon(@Valid @RequestBody SignonRequest request) {
        log.debug("Signon attempt for username: {}", request.getUsername());

        // Brute-force protection: reject requests for temporarily locked-out accounts
        if (loginAttemptService.isBlocked(request.getUsername())) {
            long remainingSeconds = loginAttemptService.getRemainingLockoutSeconds(request.getUsername());
            log.warn("Signon blocked for username {} — account temporarily locked ({} seconds remaining)",
                    request.getUsername(), remainingSeconds);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Account temporarily locked due to too many failed attempts. "
                            + "Please try again in " + remainingSeconds + " seconds.");
        }

        Optional<AccountDTO> accountOpt = accountService.getAccountForAuth(
                request.getUsername(), request.getPassword());

        if (accountOpt.isEmpty()) {
            // Record the failed attempt for lockout tracking
            loginAttemptService.recordFailedAttempt(request.getUsername());
            log.info("Authentication failed for username: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid username or password.  Signon failed.");
        }

        // Reset failure counter on successful authentication
        loginAttemptService.resetAttempts(request.getUsername());

        AccountDTO account = accountOpt.get();
        String token = jwtTokenProvider.generateToken(account.getUsername());
        SignonResponse response = new SignonResponse(token, account.getUsername(), account.getEmail());

        log.info("Authentication successful for username: {}", request.getUsername());
        return ResponseEntity.ok(response);
    }

    /**
     * Registers a new account.
     *
     * <p>Replaces the monolith's {@code AccountActionBean.newAccount()} method
     * (lines 115-121) which called {@code accountService.insertAccount(account)}.
     * The underlying 3-table atomic insert (account + profile + signon) is
     * preserved in the service layer.</p>
     *
     * <p>The underlying service performs a 3-table atomic insert (account +
     * profile + signon) then re-reads the created data from the database,
     * mirroring the monolith's post-insert reload at AccountActionBean.java
     * line 117: {@code account = accountService.getAccount(account.getUsername());}.</p>
     *
     * <p>Returns 409 Conflict if an account with the same username already
     * exists, preventing duplicate registrations.</p>
     *
     * @param accountDTO the account data to register, validated with Bean Validation
     * @return 201 Created with the created {@link AccountDTO}, or
     *         409 Conflict if the username already exists
     */
    @PostMapping
    public ResponseEntity<?> createAccount(@Valid @RequestBody AccountDTO accountDTO) {
        log.debug("Registration attempt for username: {}", accountDTO.getUsername());

        // Check for duplicate username before attempting insert
        Optional<AccountDTO> existing = accountService.getAccount(accountDTO.getUsername());
        if (existing.isPresent()) {
            log.info("Duplicate registration attempt for username: {}", accountDTO.getUsername());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Account already exists with username: " + accountDTO.getUsername());
        }

        // Mirrors monolith's AccountActionBean.newAccount() lines 116-117:
        // accountService.insertAccount(account);
        // account = accountService.getAccount(account.getUsername());
        // The service's insertAccount() already performs the post-insert reload internally.
        AccountDTO created = accountService.insertAccount(accountDTO);
        log.info("Account registered successfully for username: {}", accountDTO.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Updates an existing account.
     *
     * <p>Replaces the monolith's {@code AccountActionBean.editAccount()} method
     * (lines 134-140) which called {@code accountService.updateAccount(account)}.
     * The conditional signon update pattern (only update password if provided)
     * is preserved in the service layer.</p>
     *
     * @param username   the account userid from the URL path (source of truth)
     * @param accountDTO the updated account data, validated with Bean Validation
     * @return 200 OK with the updated {@link AccountDTO}, or
     *         404 Not Found if the account does not exist
     */
    @PutMapping("/{username}")
    public ResponseEntity<?> updateAccount(@PathVariable String username,
                                           @Valid @RequestBody AccountDTO accountDTO) {
        log.debug("Update attempt for username: {}", username);

        // Pre-check existence — mirrors the REST convention of returning 404
        // before attempting the update. This prevents the service layer's
        // RuntimeException from propagating as a 500 error.
        Optional<AccountDTO> existing = accountService.getAccount(username);
        if (existing.isEmpty()) {
            log.info("Account not found for update — username: {}", username);
            return ResponseEntity.notFound().build();
        }

        // Mirrors monolith's AccountActionBean.editAccount() lines 138-139:
        // accountService.updateAccount(account);
        // account = accountService.getAccount(account.getUsername());
        // The service's updateAccount() performs conditional password update
        // (only when password is non-null and non-empty, per monolith lines 71-72)
        // and returns the updated account via an internal getAccount() call.
        Optional<AccountDTO> updated = accountService.updateAccount(username, accountDTO);
        log.info("Account updated successfully for username: {}", username);
        return ResponseEntity.ok(updated.orElseThrow(() ->
                new RuntimeException("Account disappeared during update: " + username)));
    }

    /**
     * Retrieves an account by username.
     *
     * <p>Provides the account data that the monolith's ActionBeans obtained via
     * session attributes: {@code session.getAttribute("/actions/Account.action")}
     * in OrderActionBean (lines 55, 64, 78). In the microservice architecture,
     * this data is retrieved via REST call with JWT authentication.</p>
     *
     * @param username the account userid to retrieve
     * @return 200 OK with the {@link AccountDTO}, or
     *         404 Not Found if the account does not exist
     */
    @GetMapping("/{username}")
    public ResponseEntity<?> getAccount(@PathVariable String username) {
        log.debug("Account retrieval for username: {}", username);

        Optional<AccountDTO> account = accountService.getAccount(username);
        if (account.isEmpty()) {
            log.debug("Account not found for username: {}", username);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Account not found: " + username);
        }

        return ResponseEntity.ok(account.get());
    }
}
