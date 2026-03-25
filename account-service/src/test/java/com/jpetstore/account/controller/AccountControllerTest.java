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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.account.dto.AccountDTO;
import com.jpetstore.account.dto.SignonRequest;
import com.jpetstore.account.security.JwtTokenProvider;
import com.jpetstore.account.security.LoginAttemptService;
import com.jpetstore.account.security.SecurityConfig;
import com.jpetstore.account.service.AccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice test for {@link AccountController}.
 *
 * <p>Validates HTTP request handling, JSON response structures, status codes,
 * and service method delegation for all four REST endpoints without starting
 * a full application context. Mirrors the monolith's test patterns from
 * {@code AccountActionBeanTest.java} and {@code AccountServiceTest.java},
 * adapted for the Spring Boot 3 REST API architecture.</p>
 *
 * <h3>Endpoints Under Test</h3>
 * <ul>
 *   <li>{@code POST /api/accounts/signon} — Authentication (permitAll)</li>
 *   <li>{@code POST /api/accounts} — Registration (permitAll)</li>
 *   <li>{@code PUT /api/accounts/{username}} — Account update (authenticated)</li>
 *   <li>{@code GET /api/accounts/{username}} — Account retrieval (authenticated)</li>
 * </ul>
 *
 * <h3>Mocked Dependencies</h3>
 * <ul>
 *   <li>{@link AccountService} — Business logic layer, mocked to isolate controller</li>
 *   <li>{@link JwtTokenProvider} — JWT generation/validation, mocked for controlled token output</li>
 *   <li>{@link LoginAttemptService} — Brute-force protection, mocked to control lockout behavior</li>
 * </ul>
 *
 * <h3>Security Testing</h3>
 * <p>{@code SecurityConfig} is imported via {@code @Import} to exercise endpoint authorization
 * rules. Tests for authenticated endpoints use {@code @WithMockUser} to simulate JWT-authenticated
 * requests. Tests for unauthenticated access verify that Spring Security rejects requests
 * without valid authentication.</p>
 *
 * @see AccountController
 * @see com.jpetstore.account.security.SecurityConfig
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    // -----------------------------------------------------------------------
    // POST /api/accounts/signon — Authentication Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that a successful signon returns 200 OK with a JWT token.
     *
     * <p>Mirrors the monolith's {@code AccountActionBean.signon()} success path
     * (lines 159-177) where {@code accountService.getAccount(username, password)}
     * returns a non-null Account, after which the monolith sets
     * {@code authenticated = true} and stores the bean in the HTTP session.
     * In the microservice, the equivalent is returning a JWT token in the
     * {@link com.jpetstore.account.dto.SignonResponse}.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    void signonShouldReturnTokenOnValidCredentials() throws Exception {
        // Arrange — set up a valid account and JWT token
        AccountDTO accountDTO = new AccountDTO();
        accountDTO.setUsername("testuser");
        accountDTO.setEmail("test@test.com");

        when(loginAttemptService.isBlocked("testuser")).thenReturn(false);
        when(accountService.getAccountForAuth("testuser", "testpass"))
                .thenReturn(Optional.of(accountDTO));
        when(jwtTokenProvider.generateToken("testuser")).thenReturn("jwt-test-token");

        SignonRequest request = new SignonRequest("testuser", "testpass");

        // Act & Assert — perform POST and verify response
        mockMvc.perform(post("/api/accounts/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value("jwt-test-token"))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.email").value("test@test.com"));

        // Verify service delegation — controller must call service and JWT provider
        verify(accountService).getAccountForAuth(eq("testuser"), eq("testpass"));
        verify(jwtTokenProvider).generateToken(eq("testuser"));
        // Verify brute-force protection — successful login resets attempt counter
        verify(loginAttemptService).resetAttempts(eq("testuser"));
    }

    /**
     * Verifies that invalid credentials return 401 Unauthorized.
     *
     * <p>Mirrors the monolith's {@code AccountActionBean.signon()} failure path
     * (lines 163-167) where {@code accountService.getAccount(username, password)}
     * returns null and the error message "Invalid username or password.
     * Signon failed." is set.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    void signonShouldReturn401OnInvalidCredentials() throws Exception {
        // Arrange — service returns empty Optional for invalid credentials
        when(loginAttemptService.isBlocked("baduser")).thenReturn(false);
        when(accountService.getAccountForAuth("baduser", "badpass"))
                .thenReturn(Optional.empty());

        SignonRequest request = new SignonRequest("baduser", "badpass");

        // Act & Assert — expect 401 Unauthorized
        mockMvc.perform(post("/api/accounts/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // Verify service was called with correct arguments
        verify(accountService).getAccountForAuth(eq("baduser"), eq("badpass"));
        // Verify brute-force tracking — failed login records the attempt
        verify(loginAttemptService).recordFailedAttempt(eq("baduser"));
        // Verify JWT was NOT generated on failure
        verify(jwtTokenProvider, never()).generateToken(any());
    }

    // -----------------------------------------------------------------------
    // POST /api/accounts — Registration Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that successful account creation returns 201 Created.
     *
     * <p>Mirrors the monolith's {@code AccountActionBean.newAccount()} (lines 115-121)
     * which calls {@code accountService.insertAccount(account)} for the atomic 3-table
     * insert (account + profile + signon), then reloads the account via
     * {@code accountService.getAccount(account.getUsername())}.</p>
     *
     * <p>In the microservice, the controller delegates to
     * {@code accountService.insertAccount(AccountDTO)} which performs the same
     * 3-table transaction internally and returns the created DTO.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    void createAccountShouldReturn201OnSuccess() throws Exception {
        // Arrange — input DTO for registration
        AccountDTO inputDTO = new AccountDTO();
        inputDTO.setUsername("newuser");
        inputDTO.setPassword("newpass");
        inputDTO.setEmail("new@test.com");
        inputDTO.setFirstName("John");
        inputDTO.setLastName("Doe");

        // Created DTO returned by service after 3-table atomic insert
        AccountDTO createdDTO = new AccountDTO();
        createdDTO.setUsername("newuser");
        createdDTO.setEmail("new@test.com");
        createdDTO.setFirstName("John");
        createdDTO.setLastName("Doe");

        // No existing account with this username (duplicate check passes)
        when(accountService.getAccount("newuser")).thenReturn(Optional.empty());
        when(accountService.insertAccount(any(AccountDTO.class))).thenReturn(createdDTO);

        // Act & Assert — expect 201 Created with the created account data
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inputDTO)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.email").value("new@test.com"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"));

        // Verify the 3-table insert was delegated to the service layer
        verify(accountService).insertAccount(any(AccountDTO.class));
    }

    // -----------------------------------------------------------------------
    // PUT /api/accounts/{username} — Update Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that a successful account update returns 200 OK with updated data.
     *
     * <p>Mirrors the monolith's {@code AccountActionBean.editAccount()} (lines 134-140)
     * which calls {@code accountService.updateAccount(account)} with conditional
     * password update (only if password is non-null and non-empty), then reloads
     * the account via {@code accountService.getAccount(account.getUsername())}.</p>
     *
     * <p>The microservice controller first checks existence, then delegates the
     * update (including conditional signon/password update) to the service layer,
     * which returns the updated account data.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @WithMockUser(username = "existinguser")
    @Test
    void updateAccountShouldReturn200OnSuccess() throws Exception {
        // Arrange — existing account for existence check
        AccountDTO existingDTO = new AccountDTO();
        existingDTO.setUsername("existinguser");
        existingDTO.setEmail("old@test.com");
        existingDTO.setFirstName("Jane");
        existingDTO.setLastName("Doe");

        // Updated account returned after service processes the update
        AccountDTO updatedDTO = new AccountDTO();
        updatedDTO.setUsername("existinguser");
        updatedDTO.setEmail("updated@test.com");
        updatedDTO.setFirstName("Jane");
        updatedDTO.setLastName("Smith");

        // Input DTO with updated fields
        AccountDTO inputDTO = new AccountDTO();
        inputDTO.setUsername("existinguser");
        inputDTO.setEmail("updated@test.com");
        inputDTO.setFirstName("Jane");
        inputDTO.setLastName("Smith");

        // Existence check returns present account
        when(accountService.getAccount("existinguser")).thenReturn(Optional.of(existingDTO));
        // Update returns the updated account
        when(accountService.updateAccount(eq("existinguser"), any(AccountDTO.class)))
                .thenReturn(Optional.of(updatedDTO));

        // Act & Assert — expect 200 OK with updated account data
        mockMvc.perform(put("/api/accounts/existinguser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inputDTO)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("existinguser"))
                .andExpect(jsonPath("$.email").value("updated@test.com"))
                .andExpect(jsonPath("$.lastName").value("Smith"));

        // Verify controller delegated update to service with correct path variable
        verify(accountService).updateAccount(eq("existinguser"), any(AccountDTO.class));
    }

    /**
     * Verifies that updating a non-existent account returns 404 Not Found.
     *
     * <p>This is a defensive REST API check that prevents the service layer's
     * {@code RuntimeException("Account not found")} from propagating as an
     * uncontrolled 500 error. The controller pre-checks existence before
     * delegating to the service.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @WithMockUser(username = "nonexistent")
    @Test
    void updateAccountShouldReturn404WhenNotFound() throws Exception {
        // Arrange — no account exists with this username
        when(accountService.getAccount("nonexistent")).thenReturn(Optional.empty());

        AccountDTO inputDTO = new AccountDTO();
        inputDTO.setUsername("nonexistent");
        inputDTO.setEmail("test@test.com");
        inputDTO.setFirstName("Test");
        inputDTO.setLastName("User");

        // Act & Assert — expect 404 Not Found
        mockMvc.perform(put("/api/accounts/nonexistent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inputDTO)))
                .andExpect(status().isNotFound());

        // Verify existence check was performed
        verify(accountService).getAccount(eq("nonexistent"));
        // Verify update was NOT attempted for non-existent account
        verify(accountService, never()).updateAccount(any(), any());
    }

    /**
     * Verifies that unauthenticated PUT requests are rejected.
     *
     * <p>Tests that Spring Security's authorization rule
     * ({@code anyRequest().authenticated()}) properly blocks unauthenticated
     * access to the update endpoint. This mirrors the monolith's
     * {@code isAuthenticated()} check (AccountActionBean.java lines 195-197)
     * that guarded account modification operations.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    void updateAccountShouldReturn401WhenNotAuthenticated() throws Exception {
        // Arrange — input DTO (not relevant since security rejects first)
        AccountDTO inputDTO = new AccountDTO();
        inputDTO.setUsername("testuser");
        inputDTO.setEmail("test@test.com");
        inputDTO.setFirstName("Test");
        inputDTO.setLastName("User");

        // Act & Assert — no @WithMockUser, expect security rejection
        mockMvc.perform(put("/api/accounts/testuser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inputDTO)))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // GET /api/accounts/{username} — Retrieval Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that retrieving an existing account returns 200 OK with full data.
     *
     * <p>Mirrors the monolith's {@code AccountService.getAccount(String username)}
     * (lines 39-41) which performs a 4-table JOIN (account + profile + signon +
     * bannerdata) via the AccountMapper. In the microservice, the service assembles
     * the same combined data from individual JPA repositories.</p>
     *
     * <p>Verifies that all expected fields are present in the JSON response,
     * including profile fields (languagePreference, favouriteCategoryId, listOption,
     * bannerOption) and banner data (bannerName). Password is excluded from the
     * response per {@code @JsonProperty(access = WRITE_ONLY)} on AccountDTO.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @WithMockUser(username = "testuser")
    @Test
    void getAccountShouldReturn200WhenFound() throws Exception {
        // Arrange — full account DTO with all fields populated
        AccountDTO accountDTO = new AccountDTO();
        accountDTO.setUsername("testuser");
        accountDTO.setEmail("test@test.com");
        accountDTO.setFirstName("Test");
        accountDTO.setLastName("User");
        accountDTO.setLanguagePreference("english");
        accountDTO.setFavouriteCategoryId("DOGS");
        accountDTO.setListOption(true);
        accountDTO.setBannerOption(true);
        accountDTO.setBannerName("banner1.gif");

        when(accountService.getAccount("testuser")).thenReturn(Optional.of(accountDTO));

        // Act & Assert — expect 200 OK with all account fields
        mockMvc.perform(get("/api/accounts/testuser"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.email").value("test@test.com"))
                .andExpect(jsonPath("$.firstName").value("Test"))
                .andExpect(jsonPath("$.lastName").value("User"))
                .andExpect(jsonPath("$.languagePreference").value("english"))
                .andExpect(jsonPath("$.favouriteCategoryId").value("DOGS"))
                .andExpect(jsonPath("$.listOption").value(true))
                .andExpect(jsonPath("$.bannerOption").value(true))
                .andExpect(jsonPath("$.bannerName").value("banner1.gif"));

        // Verify service delegation
        verify(accountService).getAccount(eq("testuser"));
    }

    /**
     * Verifies that retrieving a non-existent account returns 404 Not Found.
     *
     * <p>Mirrors the monolith's behavior where
     * {@code accountMapper.getAccountByUsername()} returns null for a non-existent
     * user. In the microservice, the service returns {@code Optional.empty()},
     * and the controller maps this to a 404 response.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @WithMockUser(username = "missinguser")
    @Test
    void getAccountShouldReturn404WhenNotFound() throws Exception {
        // Arrange — service returns empty Optional for non-existent user
        when(accountService.getAccount("missinguser")).thenReturn(Optional.empty());

        // Act & Assert — expect 404 Not Found
        mockMvc.perform(get("/api/accounts/missinguser"))
                .andExpect(status().isNotFound());

        // Verify service was called with correct username
        verify(accountService).getAccount(eq("missinguser"));
    }

    /**
     * Verifies that unauthenticated GET requests are rejected.
     *
     * <p>Tests that Spring Security's authorization rule
     * ({@code anyRequest().authenticated()}) properly blocks unauthenticated
     * access to the retrieval endpoint. In the monolith, account data was
     * accessible only through the session-scoped ActionBean after authentication.
     * In the microservice, JWT authentication is required per SecurityConfig.</p>
     *
     * @throws Exception if MockMvc request processing fails
     */
    @Test
    void getAccountShouldReturn401WhenNotAuthenticated() throws Exception {
        // Act & Assert — no @WithMockUser, expect security rejection
        mockMvc.perform(get("/api/accounts/testuser"))
                .andExpect(status().isUnauthorized());
    }
}
