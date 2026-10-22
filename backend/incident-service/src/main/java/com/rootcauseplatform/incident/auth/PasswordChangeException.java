package com.rootcauseplatform.incident.auth;

/** The password was not changed: wrong current password, or the new one breaks {@link PasswordPolicy}. */
public class PasswordChangeException extends RuntimeException {

    public PasswordChangeException(String message) {
        super(message);
    }
}
