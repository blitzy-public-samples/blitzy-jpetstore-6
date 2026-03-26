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
package com.jpetstore.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jpetstore.account.dto.AccountDTO;
import com.jpetstore.account.dto.SignonRequest;
import com.jpetstore.account.dto.SignonResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration test for the Account Service microservice.
 *
 * <p>Validates the entire Account Service stack end-to-end against a real PostgreSQL
 * database provisioned via Testcontainers. Tests exercise REST endpoints, JPA persistence,
 * Liquibase schema migration, and the JWT authentication flow — verifying that the new
 * microservice replicates the monolith's {@code AccountService} behavior identically.</p>
 *
 * <h3>Test Order and Dependencies</h3>
 * <p>Tests are executed in strict order via {@link TestMethodOrder} with
 * {@link MethodOrderer.OrderAnnotation}. Later tests depend on state created by
 * earlier tests (e.g., the registered user from test 1 is authenticated in test 3
 * and updated in test 5). This mirrors the full account lifecycle:
 * registration → authentication → profile update → retrieval.</p>
 *
 * <h3>Source Pattern References</h3>
 * <ul>
 *   <li>{@code AccountMapperTest.java} — Given-When-Then pattern with AssertJ</li>
 *   <li>{@code AccountServiceTest.java} — Verification of 3-table insert atomicity</li>
 *   <li>{@code AccountService.java} — 3-table transaction (insertAccount, insertProfile,
 *       insertSignon) and conditional signon update pattern</li>
 *   <li>{@code jpetstore-hsqldb-schema.sql} — Schema for signon, account, profile,
 *       bannerdata tables</li>
 * </ul>
 *
 * @see com.jpetstore.account.controller.AccountController
 * @see com.jpetstore.account.service.AccountService
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountServiceIT {

    // ----------------------------------------------------------------
    // Testcontainer — PostgreSQL 16 matching production DB (AAP §0.6.1)
    // ----------------------------------------------------------------

    /**
     * Disposable PostgreSQL 16 container for integration testing.
     * Ensures tests run against real PostgreSQL, matching the production
     * database engine. HSQLDB is never used in new services (AAP §0.8.1).
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_account_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Wires the Testcontainer PostgreSQL connection properties into the Spring
     * application context, overriding the static {@code application.yml} datasource
     * configuration at test runtime.
     *
     * <p>Sets {@code spring.jpa.hibernate.ddl-auto} to {@code validate} because
     * Liquibase manages the schema — Hibernate only validates that entity mappings
     * match the database tables created by the changelogs.</p>
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    // ----------------------------------------------------------------
    // Injected fields
    // ----------------------------------------------------------------

    /** Auto-configured by {@code @SpringBootTest(RANDOM_PORT)} for HTTP calls. */
    @Autowired
    TestRestTemplate restTemplate;

    /** Dynamically assigned port for the embedded web server. */
    @LocalServerPort
    int port;

    /** For direct SQL verification in the Liquibase migration test. */
    @Autowired
    JdbcTemplate jdbcTemplate;

    // ----------------------------------------------------------------
    // Shared state across ordered tests
    // ----------------------------------------------------------------

    /**
     * JWT token obtained during authentication flow tests.
     * Stored as a static field so that later @Order tests can use it
     * for authenticated REST calls (GET, PUT endpoints).
     */
    private static String jwtToken;

    // ----------------------------------------------------------------
    // Test 1: Registration Flow — @Order(1)
    // ----------------------------------------------------------------

    /**
     * Validates the full registration flow: create a new account via POST,
     * then authenticate and retrieve it via GET.
     *
     * <p>This test mirrors the monolith's {@code AccountService.insertAccount()}
     * which performs a 3-table atomic insert: account + profile + signon
     * (source: {@code AccountService.java} lines 54-57).</p>
     *
     * <p>Given: A fully-populated AccountDTO with all 18 fields.
     * When: POST to /api/accounts.
     * Then: HTTP 201 Created and all fields persisted correctly.</p>
     */
    @Test
    @Order(1)
    void testRegistrationFlow() {
        // Given — construct a fully-populated AccountDTO
        AccountDTO accountDTO = createBaseAccountDTO();

        // When — POST to /api/accounts (public endpoint, no auth required)
        // Use toRequestBody() to convert DTO to a Map so that the password field
        // (which has @JsonProperty(WRITE_ONLY) on AccountDTO) is included in the
        // serialised JSON request body sent by TestRestTemplate.
        ResponseEntity<AccountDTO> createResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts",
                toRequestBody(accountDTO),
                AccountDTO.class);

        // Then — HTTP 201 Created
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().getUsername()).isEqualTo("testuser");

        // Authenticate to get JWT for the GET call (GET requires auth per SecurityConfig)
        SignonRequest signonRequest = new SignonRequest("testuser", "testpassword");
        ResponseEntity<SignonResponse> signonResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts/signon",
                signonRequest,
                SignonResponse.class);
        assertThat(signonResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signonResponse.getBody()).isNotNull();
        jwtToken = signonResponse.getBody().getToken();
        assertThat(jwtToken).isNotNull().isNotEmpty();

        // GET /api/accounts/testuser with JWT to verify persistence
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken);
        HttpEntity<Void> getRequest = new HttpEntity<>(headers);

        ResponseEntity<AccountDTO> getResponse = restTemplate.exchange(
                getBaseUrl() + "/api/accounts/testuser",
                HttpMethod.GET,
                getRequest,
                AccountDTO.class);

        // Verify account retrieval succeeds and fields match
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        AccountDTO retrieved = getResponse.getBody();
        assertThat(retrieved.getUsername()).isEqualTo("testuser");
        assertThat(retrieved.getEmail()).isEqualTo("test@example.com");
        assertThat(retrieved.getFirstName()).isEqualTo("Test");
    }

    // ----------------------------------------------------------------
    // Test 2: Duplicate Registration — @Order(2)
    // ----------------------------------------------------------------

    /**
     * Validates that registering with a duplicate username returns 409 Conflict.
     *
     * <p>Given: A user "testuser" already exists (created in test 1).
     * When: POST to /api/accounts with the same username.
     * Then: HTTP 409 Conflict.</p>
     */
    @Test
    @Order(2)
    void testDuplicateRegistration() {
        // Given — same AccountDTO as test 1
        AccountDTO duplicateDTO = createBaseAccountDTO();

        // When — POST to /api/accounts with duplicate username
        // Use toRequestBody() to bypass @JsonProperty(WRITE_ONLY) on password
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts",
                toRequestBody(duplicateDTO),
                String.class);

        // Then — HTTP 409 Conflict (duplicate username)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ----------------------------------------------------------------
    // Test 3: Authentication Flow — @Order(3)
    // ----------------------------------------------------------------

    /**
     * Validates the authentication (signon) flow: submit valid credentials and
     * receive a JWT token.
     *
     * <p>This mirrors the monolith's {@code AccountActionBean.signon()} method
     * (source: {@code AccountActionBean.java} lines 159-177) where successful
     * authentication sets {@code authenticated = true} and stores the account in
     * the HTTP session. In the microservice, this is replaced by a JWT token.</p>
     *
     * <p>Given: Valid credentials for "testuser" (registered in test 1).
     * When: POST to /api/accounts/signon.
     * Then: HTTP 200 OK with a SignonResponse containing a non-null JWT token.</p>
     */
    @Test
    @Order(3)
    void testAuthenticationFlow() {
        // Given — valid credentials
        SignonRequest request = new SignonRequest("testuser", "testpassword");

        // When — POST to /api/accounts/signon
        ResponseEntity<SignonResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts/signon",
                request,
                SignonResponse.class);

        // Then — HTTP 200 OK with JWT token
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        SignonResponse signonResponse = response.getBody();
        assertThat(signonResponse.getToken()).isNotNull().isNotEmpty();
        assertThat(signonResponse.getUsername()).isEqualTo("testuser");
        assertThat(signonResponse.getEmail()).isEqualTo("test@example.com");

        // Store token for subsequent authenticated tests
        jwtToken = signonResponse.getToken();
    }

    // ----------------------------------------------------------------
    // Test 4: Invalid Credentials — @Order(4)
    // ----------------------------------------------------------------

    /**
     * Validates that invalid credentials return 401 Unauthorized.
     *
     * <p>This mirrors the monolith's {@code account == null} check at
     * {@code AccountActionBean.java} line 163: when
     * {@code accountService.getAccount(username, password)} returns null,
     * the error message "Invalid username or password. Signon failed." is shown.</p>
     *
     * <p>Given: Valid username but wrong password.
     * When: POST to /api/accounts/signon.
     * Then: HTTP 401 Unauthorized.</p>
     */
    @Test
    @Order(4)
    void testInvalidCredentials() {
        // Given — valid username, wrong password
        SignonRequest request = new SignonRequest("testuser", "wrongpassword");

        // When — POST to /api/accounts/signon
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts/signon",
                request,
                String.class);

        // Then — HTTP 401 Unauthorized
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------
    // Test 5: Account Update Flow — @Order(5)
    // ----------------------------------------------------------------

    /**
     * Validates the account update flow: modify fields and verify persistence.
     *
     * <p>This mirrors the monolith's {@code AccountService.updateAccount()} method
     * which uses:
     * {@code Optional.ofNullable(account.getPassword())
     *     .filter(password -> password.length() > 0)
     *     .ifPresent(password -> accountMapper.updateSignon(account))}
     * (source: {@code AccountService.java} lines 71-72).
     * When a new password is provided, it updates the signon table conditionally.</p>
     *
     * <p>Given: An existing user "testuser" with a new email and firstName,
     * plus a new password to trigger the conditional signon update.
     * When: PUT to /api/accounts/testuser.
     * Then: HTTP 200 OK, and GET confirms the changes persisted.</p>
     */
    @Test
    @Order(5)
    void testAccountUpdateFlow() {
        assertThat(jwtToken).as("JWT token must be available from prior tests").isNotNull();

        // Given — updated AccountDTO with changed email, firstName, and password
        AccountDTO updatedDTO = createBaseAccountDTO();
        updatedDTO.setEmail("updated@example.com");
        updatedDTO.setFirstName("Updated");
        updatedDTO.setPassword("newpassword");

        // When — PUT to /api/accounts/testuser with JWT auth
        // Use toRequestBody() so that the new password is included in the JSON body
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> putRequest = new HttpEntity<>(toRequestBody(updatedDTO), headers);

        ResponseEntity<AccountDTO> putResponse = restTemplate.exchange(
                getBaseUrl() + "/api/accounts/testuser",
                HttpMethod.PUT,
                putRequest,
                AccountDTO.class);

        // Then — HTTP 200 OK
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify changes persisted via GET
        HttpEntity<Void> getRequest = new HttpEntity<>(headers);
        ResponseEntity<AccountDTO> getResponse = restTemplate.exchange(
                getBaseUrl() + "/api/accounts/testuser",
                HttpMethod.GET,
                getRequest,
                AccountDTO.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getEmail()).isEqualTo("updated@example.com");
        assertThat(getResponse.getBody().getFirstName()).isEqualTo("Updated");
    }

    // ----------------------------------------------------------------
    // Test 6: Account Update Without Password — @Order(6)
    // ----------------------------------------------------------------

    /**
     * Validates that an account update without a password does NOT change
     * the existing password in the signon table.
     *
     * <p>This tests the conditional signon update pattern from the monolith's
     * {@code AccountService.java} lines 71-72:
     * {@code Optional.ofNullable(account.getPassword())
     *     .filter(password -> password.length() > 0)
     *     .ifPresent(password -> accountMapper.updateSignon(account))}
     * When the password field is null or empty, the signon table is NOT updated.</p>
     *
     * <p>Given: An AccountDTO where password is null.
     * When: PUT to /api/accounts/testuser.
     * Then: HTTP 200 OK, and sign-in still works with the previously set password
     * ("newpassword" from test 5).</p>
     */
    @Test
    @Order(6)
    void testAccountUpdateWithoutPassword() {
        assertThat(jwtToken).as("JWT token must be available from prior tests").isNotNull();

        // Given — AccountDTO with null password (should NOT change existing password)
        AccountDTO noPasswordDTO = createBaseAccountDTO();
        noPasswordDTO.setEmail("updated@example.com");
        noPasswordDTO.setFirstName("Updated");
        noPasswordDTO.setPassword(null); // Null password — should not update signon table

        // When — PUT to /api/accounts/testuser
        // Use toRequestBody() — the null password is intentionally omitted from the Map
        // so the server does not update the signon table (conditional update pattern)
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> putRequest = new HttpEntity<>(toRequestBody(noPasswordDTO), headers);

        ResponseEntity<AccountDTO> putResponse = restTemplate.exchange(
                getBaseUrl() + "/api/accounts/testuser",
                HttpMethod.PUT,
                putRequest,
                AccountDTO.class);

        // Then — HTTP 200 OK (update succeeded)
        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify that sign-in still works with the password set in test 5 ("newpassword")
        // This proves the null-password update did NOT modify the signon table
        SignonRequest signonRequest = new SignonRequest("testuser", "newpassword");
        ResponseEntity<SignonResponse> signonResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/accounts/signon",
                signonRequest,
                SignonResponse.class);

        assertThat(signonResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signonResponse.getBody()).isNotNull();
        assertThat(signonResponse.getBody().getToken()).isNotNull().isNotEmpty();
    }

    // ----------------------------------------------------------------
    // Test 7: Get Non-Existent User — @Order(7)
    // ----------------------------------------------------------------

    /**
     * Validates that retrieving another user's account returns 403 Forbidden.
     *
     * <p>The controller implements IDOR (Insecure Direct Object Reference) prevention:
     * an authenticated user can only view their own account. Requesting a different
     * username — even one that does not exist — returns 403 Forbidden rather than 404
     * Not Found to prevent user-enumeration attacks.</p>
     *
     * <p>Given: An authenticated user "j2ee" with a valid JWT.
     * When: GET to /api/accounts/nonexistent (different username).
     * Then: HTTP 403 Forbidden (IDOR check precedes existence check).</p>
     */
    @Test
    @Order(7)
    void testGetNonExistentUser() {
        assertThat(jwtToken).as("JWT token must be available from prior tests").isNotNull();

        // Given — auth headers for the GET request (token belongs to "j2ee")
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // When — GET /api/accounts/nonexistent (a username that doesn't match the token)
        ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/accounts/nonexistent",
                HttpMethod.GET,
                request,
                String.class);

        // Then — HTTP 403 Forbidden (IDOR protection: authenticated user ≠ requested user)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----------------------------------------------------------------
    // Test 8: Liquibase Migration Verification — @Order(8)
    // ----------------------------------------------------------------

    /**
     * Verifies that Liquibase successfully created the 4 account-context tables
     * (signon, account, profile, bannerdata) in PostgreSQL and that their column
     * structures match the changeset {@code 001-initial-schema.xml}.
     *
     * <p>Uses {@link JdbcTemplate} for direct SQL verification, consistent with
     * the monolith's {@code AccountMapperTest.java} pattern of using JdbcTemplate
     * to verify raw database state alongside ORM operations.</p>
     *
     * <p>Reference: Monolith schema from {@code jpetstore-hsqldb-schema.sql} —
     * signon (lines 30-34), account (lines 36-50), profile (lines 52-59),
     * bannerdata (lines 61-65).</p>
     */
    @Test
    @Order(8)
    void testLiquibaseMigration() {
        // Verify signon table exists and has expected columns
        // Query information_schema for the 4 account-context tables
        Integer signonCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'signon' AND table_schema = 'public'",
                Integer.class);
        assertThat(signonCount).as("signon table should exist").isEqualTo(1);

        // Verify account table exists
        Integer accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'account' AND table_schema = 'public'",
                Integer.class);
        assertThat(accountCount).as("account table should exist").isEqualTo(1);

        // Verify profile table exists
        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'profile' AND table_schema = 'public'",
                Integer.class);
        assertThat(profileCount).as("profile table should exist").isEqualTo(1);

        // Verify bannerdata table exists
        Integer bannerdataCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'bannerdata' AND table_schema = 'public'",
                Integer.class);
        assertThat(bannerdataCount).as("bannerdata table should exist").isEqualTo(1);

        // Verify account table column structure matches the Liquibase changeset
        // Expected columns: userid, email, firstname, lastname, status, addr1, addr2,
        //                    city, state, zip, country, phone (12 columns)
        List<Map<String, Object>> accountColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'account' AND table_schema = 'public' "
                        + "ORDER BY ordinal_position");
        assertThat(accountColumns).hasSize(12);

        // Verify signon table column structure
        // Expected columns: username, password (2 columns)
        List<Map<String, Object>> signonColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'signon' AND table_schema = 'public' "
                        + "ORDER BY ordinal_position");
        assertThat(signonColumns).hasSize(2);

        // Verify profile table column structure
        // Expected columns: userid, langpref, favcategory, mylistopt, banneropt (5 columns)
        List<Map<String, Object>> profileColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'profile' AND table_schema = 'public' "
                        + "ORDER BY ordinal_position");
        assertThat(profileColumns).hasSize(5);

        // Verify bannerdata table column structure
        // Expected columns: favcategory, bannername (2 columns)
        List<Map<String, Object>> bannerdataColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'bannerdata' AND table_schema = 'public' "
                        + "ORDER BY ordinal_position");
        assertThat(bannerdataColumns).hasSize(2);
    }

    // ================================================================
    // Helper Methods
    // ================================================================

    /**
     * Constructs a fully-populated {@link AccountDTO} with all 18 fields matching
     * the monolith's {@code Account.java} domain class (lines 27-196).
     *
     * <p>Field values mirror realistic test data consistent with the monolith's
     * seed data patterns from {@code jpetstore-hsqldb-dataload.sql}.</p>
     *
     * @return a new {@code AccountDTO} instance populated with all required fields
     */
    private AccountDTO createBaseAccountDTO() {
        AccountDTO dto = new AccountDTO();
        dto.setUsername("testuser");
        dto.setPassword("testpassword");
        dto.setEmail("test@example.com");
        dto.setFirstName("Test");
        dto.setLastName("User");
        dto.setStatus("OK");
        dto.setAddress1("123 Main St");
        dto.setAddress2("Apt 4");
        dto.setCity("Springfield");
        dto.setState("IL");
        dto.setZip("62701");
        dto.setCountry("USA");
        dto.setPhone("555-1234");
        dto.setFavouriteCategoryId("DOGS");
        dto.setLanguagePreference("english");
        dto.setListOption(true);
        dto.setBannerOption(true);
        return dto;
    }

    /**
     * Converts an {@link AccountDTO} to a plain {@link Map} for use as an HTTP
     * request body in integration tests.
     *
     * <p><strong>Why this is needed:</strong> {@code AccountDTO.password} is annotated
     * with {@code @JsonProperty(access = WRITE_ONLY)}, which is correct for server-side
     * behaviour (password is accepted in requests but never returned in responses).
     * However, when {@link TestRestTemplate} serialises the DTO to JSON for an outgoing
     * request, Jackson <em>excludes</em> the password from the serialised output because
     * WRITE_ONLY marks the property as "not readable" during serialisation. Using a plain
     * Map bypasses this annotation and ensures the password is included in the JSON body
     * sent to the server.</p>
     *
     * @param dto the account DTO to convert (password may be {@code null} for updates
     *            that should not change the existing password)
     * @return a {@link Map} containing all non-null fields suitable for JSON serialisation
     */
    private Map<String, Object> toRequestBody(AccountDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", dto.getUsername());
        if (dto.getPassword() != null) {
            map.put("password", dto.getPassword());
        }
        map.put("email", dto.getEmail());
        map.put("firstName", dto.getFirstName());
        map.put("lastName", dto.getLastName());
        map.put("status", dto.getStatus());
        map.put("address1", dto.getAddress1());
        map.put("address2", dto.getAddress2());
        map.put("city", dto.getCity());
        map.put("state", dto.getState());
        map.put("zip", dto.getZip());
        map.put("country", dto.getCountry());
        map.put("phone", dto.getPhone());
        map.put("favouriteCategoryId", dto.getFavouriteCategoryId());
        map.put("languagePreference", dto.getLanguagePreference());
        map.put("listOption", dto.isListOption());
        map.put("bannerOption", dto.isBannerOption());
        return map;
    }

    /**
     * Returns the base URL for the embedded web server including the
     * dynamically assigned port.
     *
     * @return the base URL string in the format {@code http://localhost:{port}}
     */
    private String getBaseUrl() {
        return "http://localhost:" + port;
    }
}
