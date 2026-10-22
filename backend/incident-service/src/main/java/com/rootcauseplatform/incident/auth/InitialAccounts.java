package com.rootcauseplatform.incident.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.rootcauseplatform.incident.security.AuthProperties;

/**
 * Creates the accounts configured with {@code AUTH_BOOTSTRAP_ADMIN_*} (and optionally
 * {@code AUTH_BOOTSTRAP_VIEWER_*}) at startup, if they do not exist yet. Existing accounts are
 * never changed, so a password changed later is not reset by a restart. Meant for local
 * development and first setup; a password that breaks {@link PasswordPolicy} stops the service.
 */
@Component
class InitialAccounts implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAccounts.class);

    private final AuthService authService;
    private final UserAccountRepository users;
    private final AuthProperties.Bootstrap bootstrap;

    InitialAccounts(AuthService authService, UserAccountRepository users, AuthProperties properties) {
        this.authService = authService;
        this.users = users;
        this.bootstrap = properties.bootstrap();
    }

    @Override
    public void run(ApplicationArguments args) {
        create(bootstrap.admin(), Role.ADMIN, "AUTH_BOOTSTRAP_ADMIN_PASSWORD");
        create(bootstrap.viewer(), Role.VIEWER, "AUTH_BOOTSTRAP_VIEWER_PASSWORD");
        if (users.count() == 0) {
            log.atWarn()
                    .setMessage("No user accounts exist. Set AUTH_BOOTSTRAP_ADMIN_USERNAME and "
                            + "AUTH_BOOTSTRAP_ADMIN_PASSWORD to create the first administrator.")
                    .addKeyValue("action", "auth.bootstrap.no_accounts")
                    .log();
        }
    }

    private void create(AuthProperties.Account account, Role role, String passwordVariable) {
        if (account == null || !account.configured()) {
            return;
        }
        try {
            authService.createIfMissing(account.username(), displayName(account), account.password(), role);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(passwordVariable + " is not acceptable: " + ex.getMessage());
        }
    }

    private static String displayName(AuthProperties.Account account) {
        String name = account.displayName();
        return name == null || name.isBlank() ? account.username().trim() : name.trim();
    }
}
