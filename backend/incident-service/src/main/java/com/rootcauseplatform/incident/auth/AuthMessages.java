package com.rootcauseplatform.incident.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the authentication API. Passwords are never returned or logged. */
public final class AuthMessages {

    private AuthMessages() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[]";
        }
    }

    public record UserResponse(UUID id, String username, String displayName, Role role) {

        static UserResponse from(UserAccount user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
        }
    }

    /**
     * The signed-in user. The access token itself is only in the HttpOnly cookie; {@code csrfToken}
     * must be sent in {@code X-CSRF-Token} with state-changing requests that use the cookie.
     */
    public record SessionResponse(UserResponse user, Instant expiresAt, String csrfToken) {
    }
}
