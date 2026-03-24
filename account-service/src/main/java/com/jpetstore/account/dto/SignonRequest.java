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
package com.jpetstore.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body DTO for the {@code POST /api/accounts/signon} authentication endpoint.
 *
 * <p>Captures the username and password submitted by the client for authentication,
 * replacing the monolith's Stripes {@code @Validate(required = true, on = {"signon"})}
 * annotations on {@code AccountActionBean.setUsername()} and
 * {@code AccountActionBean.setPassword()} with Jakarta Bean Validation
 * {@link NotBlank} constraints.</p>
 *
 * <p>This is a pure data carrier with no business logic. Validation is triggered
 * by {@code @Valid} on the controller method parameter.</p>
 *
 * @see jakarta.validation.constraints.NotBlank
 */
public class SignonRequest {

    /**
     * The username for authentication.
     * Must not be blank — replaces the monolith's
     * {@code @Validate(required = true, on = {"signon"})} on
     * {@code AccountActionBean.setUsername()} (line 76).
     */
    @NotBlank
    private String username;

    /**
     * The password for authentication.
     * Must not be blank — replaces the monolith's
     * {@code @Validate(required = true, on = {"signon"})} on
     * {@code AccountActionBean.setPassword()} (line 85).
     */
    @NotBlank
    private String password;

    /**
     * Default no-arg constructor required for JSON deserialization by Jackson.
     */
    public SignonRequest() {
        // Jackson deserialization constructor
    }

    /**
     * Parameterized constructor for convenience and testability.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     */
    public SignonRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Returns the username for authentication.
     *
     * @return the username, never {@code null} after validation
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the username for authentication.
     *
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the password for authentication.
     *
     * @return the password, never {@code null} after validation
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password for authentication.
     *
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }
}
