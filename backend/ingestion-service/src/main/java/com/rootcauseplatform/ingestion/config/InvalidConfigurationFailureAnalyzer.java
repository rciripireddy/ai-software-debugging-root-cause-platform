package com.rootcauseplatform.ingestion.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports invalid configuration as a short list instead of a stack trace. */
public class InvalidConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
        return new FailureAnalysis(
                "The ingestion-service configuration is invalid:\n - " + String.join("\n - ", cause.problems()),
                "Set the listed environment variables (see .env.example and docs/configuration.md).",
                cause);
    }
}
