package com.rootcauseplatform.incident.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sign-in, the current account, password changes and the creation of accounts. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter limiter;
    /** Checked for unknown usernames, so their response takes as long as a wrong password. */
    private final String unknownUserHash;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, LoginAttemptLimiter limiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws AuthenticationFailedException for an unknown username, a wrong password or a
     *                                       disabled account (one message for all three)
     * @throws com.rootcauseplatform.incident.service.RateLimitExceededException after too many failures
     */
    @Transactional(readOnly = true)
    public UserAccount authenticate(String username, String password, String client) {
        String name = normalizeUsername(username);
        limiter.checkAllowed(name, client);
        Optional<UserAccount> account = users.findByUsername(name);
        boolean passwordMatches = matches(password, account.map(UserAccount::getPasswordHash).orElse(unknownUserHash));
        String failure = account.isEmpty() ? "unknown_user"
                : !passwordMatches ? "wrong_password"
                : !account.get().isEnabled() ? "account_disabled"
                : null;
        if (failure != null) {
            limiter.recordFailure(name, client);
            log.atWarn()
                    .setMessage("Sign-in failed")
                    .addKeyValue("action", "auth.login.failure")
                    .addKeyValue("username", name)
                    .addKeyValue("reason", failure)
                    .addKeyValue("client", client)
                    .log();
            throw AuthenticationFailedException.invalidCredentials();
        }
        UserAccount user = account.get();
        limiter.recordSuccess(name);
        log.atInfo()
                .setMessage("Signed in")
                .addKeyValue("action", "auth.login.success")
                .addKeyValue("userId", user.getId())
                .addKeyValue("username", user.getUsername())
                .addKeyValue("role", user.getRole())
                .addKeyValue("client", client)
                .log();
        return user;
    }

    /** The signed-in account, if it still exists and is enabled. */
    @Transactional(readOnly = true)
    public UserAccount currentUser(String userId) {
        return parseId(userId).flatMap(users::findById)
                .filter(UserAccount::isEnabled)
                .orElseThrow(AuthenticationFailedException::accountUnavailable);
    }

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword, String client) {
        UserAccount user = currentUser(userId);
        limiter.checkAllowed(user.getUsername(), client);
        if (!matches(currentPassword, user.getPasswordHash())) {
            limiter.recordFailure(user.getUsername(), client);
            log.atWarn()
                    .setMessage("Password change refused: wrong current password")
                    .addKeyValue("action", "auth.password_change.failure")
                    .addKeyValue("userId", user.getId())
                    .log();
            throw new PasswordChangeException("The current password is incorrect.");
        }
        PasswordPolicy.violation(newPassword, user.getUsername()).ifPresent(reason -> {
            throw new PasswordChangeException(reason);
        });
        if (newPassword.equals(currentPassword)) {
            throw new PasswordChangeException("The new password must differ from the current password.");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        log.atInfo()
                .setMessage("Password changed")
                .addKeyValue("action", "auth.password_change.success")
                .addKeyValue("userId", user.getId())
                .log();
    }

    /**
     * Creates an account unless one with this username exists; an existing account is never
     * changed. Returns whether it was created.
     */
    @Transactional
    public boolean createIfMissing(String username, String displayName, String password, Role role) {
        String name = normalizeUsername(username);
        if (users.findByUsername(name).isPresent()) {
            return false;
        }
        PasswordPolicy.violation(password, name).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        try {
            UserAccount user = users.saveAndFlush(new UserAccount(name, displayName, passwordEncoder.encode(password),
                    role));
            log.atInfo()
                    .setMessage("User account created")
                    .addKeyValue("action", "auth.user.create")
                    .addKeyValue("userId", user.getId())
                    .addKeyValue("username", name)
                    .addKeyValue("role", role)
                    .log();
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false; // created at the same time by another instance
        }
    }

    private boolean matches(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException ex) {
            return false; // e.g. longer than BCrypt's 72 bytes
        }
    }

    private static Optional<UUID> parseId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
