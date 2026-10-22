package com.rootcauseplatform.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

import com.rootcauseplatform.ingestion.ApiClient;
import com.rootcauseplatform.ingestion.TestAuth;
import com.rootcauseplatform.ingestion.TestcontainersConfiguration;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "app.ingestion.rate-limit-per-minute=3")
@Import(TestcontainersConfiguration.class)
class IngestionRateLimitTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void eventsAboveTheLimitPerMinuteAreRejectedWithRetryAfter() {
        ApiClient producer = new ApiClient(port).withHeaders(TestAuth.apiKey());
        Map<String, Object> event = Map.of("application", "rate-limit-test", "service", "api", "environment", "test",
                "timestamp", "2026-10-22T10:00:00Z", "severity", "ERROR", "message", "Failure");

        for (int i = 0; i < 3; i++) {
            assertThat(producer.postJson("/api/v1/logs", event).status()).isEqualTo(201);
        }
        ApiClient.Response limited = producer.postJson("/api/v1/logs", event);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json().get("title").asString()).isEqualTo("Too many requests");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 60L);
        assertThat(TestAuth.client(port).get("/api/v1/logs?application=rate-limit-test").json().get("totalItems")
                .asInt()).as("reading is not limited, and only accepted events were stored").isEqualTo(3);
    }
}
