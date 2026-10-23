package com.rootcauseplatform.ingestion.config;

import java.util.List;

/** Critical settings are missing or invalid; the service does not start. */
public class InvalidConfigurationException extends RuntimeException {

    private final List<String> problems;

    public InvalidConfigurationException(List<String> problems) {
        super("Invalid configuration: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
