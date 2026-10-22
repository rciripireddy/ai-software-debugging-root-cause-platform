package com.rootcauseplatform.incident.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** {@code username} must already be in lower case (see {@link AuthService#normalizeUsername}). */
    Optional<UserAccount> findByUsername(String username);
}
