package com.rootcauseplatform.incident.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Minimum rules for new passwords: 12 to 72 bytes (BCrypt uses at most 72 bytes) and not the
 * username. Length matters more than character classes, so none are required.
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** The reason the password is not acceptable, if any. */
    public static Optional<String> violation(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("The password must have at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("The password must not be longer than " + MAX_BYTES + " bytes.");
        }
        if (password.equalsIgnoreCase(username)) {
            return Optional.of("The password must not be the username.");
        }
        return Optional.empty();
    }
}
