package com.jpetstore.account.dto;

/**
 * Response DTO for the {@code POST /api/accounts/signon} authentication endpoint.
 *
 * <p>Returned upon successful authentication, this DTO carries the JWT token,
 * the authenticated username, and the user's email address. It replaces the
 * monolith's session-scoped authentication pattern where
 * {@code AccountActionBean.authenticated = true} and the bean instance was
 * stored in the HTTP session via
 * {@code session.setAttribute("accountBean", this)}.
 *
 * <h3>Session Externalization Mapping</h3>
 * <table>
 *   <tr><th>Monolith Pattern</th><th>Microservice Pattern</th></tr>
 *   <tr>
 *     <td>{@code AccountActionBean.authenticated = true}</td>
 *     <td>{@code SignonResponse.token} — JWT presence indicates authentication</td>
 *   </tr>
 *   <tr>
 *     <td>{@code session.setAttribute("accountBean", this)}</td>
 *     <td>{@code SignonResponse.token} — JWT contains username claim</td>
 *   </tr>
 *   <tr>
 *     <td>{@code account.getUsername()} after signon</td>
 *     <td>{@code SignonResponse.username} — returned directly</td>
 *   </tr>
 *   <tr>
 *     <td>{@code account.getEmail()} after signon</td>
 *     <td>{@code SignonResponse.email} — returned directly</td>
 *   </tr>
 *   <tr>
 *     <td>{@code account.setPassword(null)} — clears password after auth</td>
 *     <td>Password is NEVER included in SignonResponse — security by design</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Security note:</strong> This DTO intentionally omits the password
 * field. The monolith nullifies the password after authentication
 * ({@code account.setPassword(null)}); the microservice achieves this by simply
 * never exposing the password in the response payload.
 *
 * @see com.jpetstore.account.controller.AccountController
 * @see com.jpetstore.account.security.JwtTokenProvider
 */
public class SignonResponse {

    /**
     * JWT token issued upon successful authentication.
     *
     * <p>This token replaces the monolith's session-scoped {@code authenticated}
     * flag and the {@code accountBean} session attribute. It contains
     * {@code username} and {@code accountId} claims and is intended to be sent
     * by the client as an {@code Authorization: Bearer} header or stored in an
     * HTTP-only cookie for subsequent requests.
     */
    private String token;

    /**
     * The authenticated user's username.
     *
     * <p>Maps from the {@code account.userid} column in the database. Matches
     * the monolith's {@code account.getUsername()} value returned after a
     * successful signon in {@code AccountActionBean.signon()}.
     */
    private String username;

    /**
     * The authenticated user's email address.
     *
     * <p>Maps from the {@code account.email} column in the database. Included
     * in the response for convenience so clients can display user information
     * without an additional API call.
     */
    private String email;

    /**
     * Default no-argument constructor.
     *
     * <p>Required for Jackson JSON deserialization/serialization. Constructs an
     * empty {@code SignonResponse} instance with all fields set to {@code null}.
     */
    public SignonResponse() {
        // No-arg constructor for Jackson serialization
    }

    /**
     * Constructs a fully-populated {@code SignonResponse}.
     *
     * @param token    the JWT token issued upon successful authentication;
     *                 must not be {@code null} for a valid response
     * @param username the authenticated user's username; corresponds to
     *                 {@code account.userid} in the database
     * @param email    the authenticated user's email address; corresponds to
     *                 {@code account.email} in the database
     */
    public SignonResponse(String token, String username, String email) {
        this.token = token;
        this.username = username;
        this.email = email;
    }

    /**
     * Returns the JWT token.
     *
     * @return the JWT token string, or {@code null} if not set
     */
    public String getToken() {
        return token;
    }

    /**
     * Sets the JWT token.
     *
     * @param token the JWT token string
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Returns the authenticated user's username.
     *
     * @return the username, or {@code null} if not set
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the authenticated user's username.
     *
     * @param username the username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the authenticated user's email address.
     *
     * @return the email address, or {@code null} if not set
     */
    public String getEmail() {
        return email;
    }

    /**
     * Sets the authenticated user's email address.
     *
     * @param email the email address
     */
    public void setEmail(String email) {
        this.email = email;
    }

}
