package com.rootcauseplatform.incident.auth;

/**
 * Sign-in failed, or the signed-in account can no longer be used. The message is the same for
 * an unknown username, a wrong password and a disabled account, so it does not reveal which
 * accounts exist.
 */
public class AuthenticationFailedException extends RuntimeException {

    private final String title;

    private AuthenticationFailedException(String title, String message) {
        super(message);
        this.title = title;
    }

    static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("Invalid credentials", "The username or password is incorrect.");
    }

    static AuthenticationFailedException accountUnavailable() {
        return new AuthenticationFailedException("Authentication required",
                "Your session is no longer valid. Sign in again.");
    }

    public String getTitle() {
        return title;
    }
}
