package com.rootcauseplatform.ingestion.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Checks the critical settings before the application context starts, so a missing or invalid
 * value stops the service with one clear message instead of a connection error later. Messages
 * name the environment variable but never contain its value. Registered in
 * {@code META-INF/spring.factories}.
 */
public class RuntimeConfigurationValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final int MIN_SECRET_BYTES = 32;

    private static final Pattern POSTGRES_URL = Pattern.compile("jdbc:postgresql://[^/:?#\\s]+(:(\\d{1,5}))?/[^/?#\\s]+(\\?.*)?");
    private static final Pattern HOST_AND_PORT = Pattern.compile("[^:,\\s]+:(\\d{1,5})");

    /** Secret settings of this service, as property name and environment variable. */
    private static final List<String[]> SECRETS = List.of(
            new String[] {"app.auth.jwt-secret", "AUTH_JWT_SECRET"},
            new String[] {"app.auth.service-token", "INTERNAL_SERVICE_TOKEN"},
            new String[] {"app.auth.ingestion-api-key", "INGESTION_API_KEY"});

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        List<String> problems = validate(event.getEnvironment());
        if (!problems.isEmpty()) {
            throw new InvalidConfigurationException(problems);
        }
    }

    static List<String> validate(Environment environment) {
        List<String> problems = new ArrayList<>();
        var url = POSTGRES_URL.matcher(environment.getProperty("spring.datasource.url", ""));
        if (!url.matches() || (url.group(2) != null && !isPort(url.group(2)))) {
            problems.add("DB_HOST, DB_PORT and DB_NAME must form a valid PostgreSQL address "
                    + "(host name, port 1-65535, database name)");
        }
        if (!isHostAndPortList(environment.getProperty("spring.kafka.bootstrap-servers", ""))) {
            problems.add("KAFKA_BOOTSTRAP_SERVERS must be a comma-separated list of host:port entries");
        }
        String serverPort = environment.getProperty("server.port", "");
        if (!serverPort.matches("\\d{1,5}") || Integer.parseInt(serverPort) > 65_535) {
            problems.add("SERVER_PORT must be a port number");
        }
        for (String[] secret : SECRETS) {
            String value = environment.getProperty(secret[0], "");
            if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                problems.add(secret[1] + " must be set to a random value of at least " + MIN_SECRET_BYTES
                        + " characters (see .env.example)");
            }
        }
        return problems;
    }

    private static boolean isHostAndPortList(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (String entry : value.split(",")) {
            var matcher = HOST_AND_PORT.matcher(entry.strip());
            if (!matcher.matches() || !isPort(matcher.group(1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPort(String digits) {
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65_535;
    }
}
