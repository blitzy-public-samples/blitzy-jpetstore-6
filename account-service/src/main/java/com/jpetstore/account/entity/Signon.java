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
package com.jpetstore.account.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapping to the {@code signon} PostgreSQL table in the Account Service.
 *
 * <p>This table stores authentication credentials consisting of a username (primary key)
 * and a password. In the original monolith, the signon table is joined with the account,
 * profile, and bannerdata tables via the shared {@code username/userid} key. In this
 * microservice architecture, Signon is managed as an independent entity — there is no
 * JPA relationship to the {@link Account} entity. The application service layer
 * ({@code AccountService}) coordinates operations across both entities.</p>
 *
 * <p>Schema origin: {@code jpetstore-hsqldb-schema.sql} lines 30-34:</p>
 * <pre>
 * CREATE TABLE signon (
 *     username varchar(25) NOT NULL,
 *     password varchar(25) NOT NULL,
 *     CONSTRAINT pk_signon PRIMARY KEY (username)
 * );
 * </pre>
 *
 * @see Account
 */
@Entity
@Table(name = "signon")
public class Signon implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The username serving as the primary key for authentication.
     * Corresponds to {@code account.userid} in the account table.
     */
    @Id
    @Column(name = "username", length = 25, nullable = false)
    private String username;

    /**
     * The user's password for authentication.
     * Stored as a plain string matching the original monolith behavior.
     */
    @Column(name = "password", length = 25, nullable = false)
    private String password;

    /**
     * Default no-argument constructor required by the JPA specification.
     */
    public Signon() {
        // JPA requires a no-arg constructor
    }

    /**
     * Parameterized constructor for convenient instantiation.
     *
     * @param username the username (primary key), must not be null
     * @param password the password, must not be null
     */
    public Signon(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Returns the username (primary key).
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the username (primary key).
     *
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the password.
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password.
     *
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Compares this Signon entity with another object for equality.
     * Two Signon entities are considered equal if they have the same {@code username}
     * (primary key), following JPA entity identity semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects are equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Signon signon = (Signon) o;
        return Objects.equals(username, signon.username);
    }

    /**
     * Returns a hash code based on the {@code username} primary key field.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    /**
     * Returns a string representation of this Signon entity for debugging purposes.
     * <p><strong>SECURITY NOTE:</strong> The password field is intentionally excluded
     * from the output to prevent accidental credential exposure in logs.</p>
     *
     * @return a string representation containing only the username
     */
    @Override
    public String toString() {
        return "Signon{username='" + username + "'}";
    }
}
