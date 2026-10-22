package com.rootcauseplatform.incident.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigurationTests {

    @Test
    void missingOrShortSecretsStopStartupWithoutRevealingTheValue() {
        assertThatThrownBy(() -> SecurityConfiguration.requireSecret(null, "AUTH_JWT_SECRET"))
                .hasMessageContaining("AUTH_JWT_SECRET must be set");
        assertThatThrownBy(() -> SecurityConfiguration.requireSecret("short-secret-value", "AUTH_JWT_SECRET"))
                .hasMessageContaining("at least 32 characters")
                .hasMessageNotContaining("short-secret-value");
        assertThat(SecurityConfiguration.requireSecret("x".repeat(32), "AUTH_JWT_SECRET")).hasSize(32);
    }

    @Test
    void propertiesNeverPrintSecrets() {
        var properties = new AuthProperties("jwt-secret-value", "issuer", "audience", null, "rcp_session", false,
                "service-token-value", null, new AuthProperties.Bootstrap(
                        new AuthProperties.Account("admin", "admin-password-value", "Admin"), null));

        assertThat(properties.toString())
                .doesNotContain("jwt-secret-value", "service-token-value", "admin-password-value")
                .contains("admin");
    }
}
