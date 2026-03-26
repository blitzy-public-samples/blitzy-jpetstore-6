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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory brute-force protection service for the signon endpoint.
 *
 * <p>Tracks failed login attempts per username using a {@link ConcurrentHashMap}. When the
 * number of consecutive failed attempts reaches {@link #MAX_ATTEMPTS} within the
 * {@link #LOCKOUT_DURATION_MS lockout window}, the account is temporarily locked out.
 * Successful authentication resets the failure counter for that username.</p>
 *
 * <h3>Design Notes</h3>
 * <ul>
 *   <li>This implementation uses an in-memory store which is appropriate for a single
 *       instance. For horizontal scaling, the attempt tracking should be moved to Redis
 *       (available in the account-service dependency set).</li>
 *   <li>Expired entries are lazily cleaned on access. A scheduled cleanup could be added
 *       for long-running instances with many distinct usernames.</li>
 *   <li>The lockout is per-username, not per-IP, to prevent credential stuffing attacks
 *       where the attacker rotates source IPs.</li>
 * </ul>
 *
 * @see AccountController
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /**
     * Maximum consecutive failed attempts before the account is temporarily locked.
     * Set to 5 per OWASP brute-force protection guidelines.
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Duration in milliseconds for which the lockout remains active after reaching
     * {@link #MAX_ATTEMPTS}. Set to 15 minutes (900,000 ms).
     */
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L;

    /**
     * Thread-safe map tracking login attempts per username. Each entry records the
     * number of consecutive failures and the timestamp of the first failure in the
     * current window.
     */
    private final ConcurrentMap<String, AttemptRecord> attemptsCache = new ConcurrentHashMap<>();

    /**
     * Records a failed login attempt for the given username.
     *
     * <p>Increments the failure counter. If this is the first failure in a new window,
     * the window start timestamp is recorded. If the existing window has expired,
     * the counter is reset to 1 with a new window start.</p>
     *
     * @param username the username that failed authentication
     */
    public void recordFailedAttempt(String username) {
        attemptsCache.compute(username, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || isWindowExpired(existing, now)) {
                // Start a new tracking window
                return new AttemptRecord(1, now);
            }
            // Increment within current window
            return new AttemptRecord(existing.attempts() + 1, existing.windowStart());
        });
        log.debug("Failed login attempt recorded for username: {}", username);
    }

    /**
     * Resets the failure counter for the given username after a successful login.
     *
     * @param username the username that successfully authenticated
     */
    public void resetAttempts(String username) {
        attemptsCache.remove(username);
    }

    /**
     * Checks whether the given username is currently locked out due to
     * excessive failed login attempts.
     *
     * <p>Returns {@code true} if the username has reached {@link #MAX_ATTEMPTS}
     * consecutive failures and the lockout window has not yet expired.
     * Expired records are lazily cleaned on this check.</p>
     *
     * @param username the username to check
     * @return {@code true} if the account is temporarily locked out, {@code false} otherwise
     */
    public boolean isBlocked(String username) {
        AttemptRecord record = attemptsCache.get(username);
        if (record == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (isWindowExpired(record, now)) {
            // Expired — clean up lazily
            attemptsCache.remove(username);
            return false;
        }
        return record.attempts() >= MAX_ATTEMPTS;
    }

    /**
     * Returns the number of remaining seconds until the lockout expires for the
     * given username, or {@code 0} if the user is not locked out.
     *
     * @param username the username to check
     * @return remaining lockout seconds, or 0 if not locked out
     */
    public long getRemainingLockoutSeconds(String username) {
        AttemptRecord record = attemptsCache.get(username);
        if (record == null || record.attempts() < MAX_ATTEMPTS) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - record.windowStart();
        long remaining = LOCKOUT_DURATION_MS - elapsed;
        return remaining > 0 ? remaining / 1000 : 0;
    }

    /**
     * Determines whether the tracking window for the given record has expired.
     *
     * @param record the attempt record to check
     * @param now    the current time in milliseconds
     * @return {@code true} if the window has expired
     */
    private boolean isWindowExpired(AttemptRecord record, long now) {
        return (now - record.windowStart()) > LOCKOUT_DURATION_MS;
    }

    /**
     * Immutable record tracking the number of failed attempts and the start of the
     * current tracking window for a specific username.
     *
     * @param attempts    the number of consecutive failed attempts
     * @param windowStart the epoch-millisecond timestamp when the first failure was recorded
     */
    private record AttemptRecord(int attempts, long windowStart) {
    }
}
