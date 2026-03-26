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
package com.jpetstore.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoginAttemptService}.
 *
 * <p>Validates brute-force protection logic including attempt recording,
 * lockout enforcement after MAX_ATTEMPTS (5), and lockout expiration.</p>
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    @DisplayName("New username should not be blocked")
    void shouldNotBlockNewUsername() {
        assertThat(service.isBlocked("newuser")).isFalse();
    }

    @Test
    @DisplayName("Should not be blocked after fewer than MAX_ATTEMPTS failures")
    void shouldNotBlockAfterFewAttempts() {
        service.recordFailedAttempt("testuser");
        service.recordFailedAttempt("testuser");
        service.recordFailedAttempt("testuser");
        service.recordFailedAttempt("testuser");

        assertThat(service.isBlocked("testuser")).isFalse();
    }

    @Test
    @DisplayName("Should block after MAX_ATTEMPTS (5) consecutive failures")
    void shouldBlockAfterMaxAttempts() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("testuser");
        }

        assertThat(service.isBlocked("testuser")).isTrue();
    }

    @Test
    @DisplayName("Should block after more than MAX_ATTEMPTS failures")
    void shouldBlockAfterExceedingMaxAttempts() {
        for (int i = 0; i < 7; i++) {
            service.recordFailedAttempt("testuser");
        }

        assertThat(service.isBlocked("testuser")).isTrue();
    }

    @Test
    @DisplayName("Should reset attempts after successful login")
    void shouldResetAttemptsOnSuccess() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("testuser");
        }
        assertThat(service.isBlocked("testuser")).isTrue();

        service.resetAttempts("testuser");

        assertThat(service.isBlocked("testuser")).isFalse();
    }

    @Test
    @DisplayName("Should track attempts independently per username")
    void shouldTrackAttemptsPerUsername() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("user1");
        }

        assertThat(service.isBlocked("user1")).isTrue();
        assertThat(service.isBlocked("user2")).isFalse();
    }

    @Test
    @DisplayName("getRemainingLockoutSeconds should return 0 for non-blocked user")
    void shouldReturnZeroLockoutForNonBlockedUser() {
        assertThat(service.getRemainingLockoutSeconds("newuser")).isZero();
    }

    @Test
    @DisplayName("getRemainingLockoutSeconds should return 0 for user below threshold")
    void shouldReturnZeroLockoutForBelowThreshold() {
        service.recordFailedAttempt("testuser");
        service.recordFailedAttempt("testuser");

        assertThat(service.getRemainingLockoutSeconds("testuser")).isZero();
    }

    @Test
    @DisplayName("getRemainingLockoutSeconds should return positive value for blocked user")
    void shouldReturnPositiveLockoutForBlockedUser() {
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("testuser");
        }

        long remaining = service.getRemainingLockoutSeconds("testuser");
        assertThat(remaining).isPositive();
        // Lockout is 15 minutes = 900 seconds max
        assertThat(remaining).isLessThanOrEqualTo(900);
    }

    @Test
    @DisplayName("Reset should be safe for unknown usernames")
    void shouldHandleResetForUnknownUser() {
        // Should not throw
        service.resetAttempts("nonexistent");
        assertThat(service.isBlocked("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("Should re-block user after reset and reaching max attempts again")
    void shouldReBlockAfterResetAndMaxAttempts() {
        // Block once
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("testuser");
        }
        assertThat(service.isBlocked("testuser")).isTrue();

        // Reset
        service.resetAttempts("testuser");
        assertThat(service.isBlocked("testuser")).isFalse();

        // Block again
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("testuser");
        }
        assertThat(service.isBlocked("testuser")).isTrue();
    }

    @Test
    @DisplayName("Should handle concurrent-like recording for different users")
    void shouldHandleMultipleUsersIndependently() {
        // Interleave attempts for multiple users
        for (int i = 0; i < 5; i++) {
            service.recordFailedAttempt("alice");
            service.recordFailedAttempt("bob");
        }

        assertThat(service.isBlocked("alice")).isTrue();
        assertThat(service.isBlocked("bob")).isTrue();

        // Reset only alice
        service.resetAttempts("alice");
        assertThat(service.isBlocked("alice")).isFalse();
        assertThat(service.isBlocked("bob")).isTrue();
    }

    @Test
    @DisplayName("Single failed attempt should not trigger lockout")
    void shouldNotLockAfterSingleAttempt() {
        service.recordFailedAttempt("testuser");
        assertThat(service.isBlocked("testuser")).isFalse();
        assertThat(service.getRemainingLockoutSeconds("testuser")).isZero();
    }
}
