package com.rootcauseplatform.incident.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts the Kafka listeners once the application is ready, and keeps retrying while that fails.
 *
 * <p>The listeners are not started with the application context
 * ({@code spring.kafka.listener.auto-startup: false}): while the broker's host name cannot be
 * resolved (for example, its container is stopped), creating a consumer throws, which would stop
 * the whole service and send it into a restart loop. This way the REST API starts regardless,
 * and the consumer begins as soon as Kafka is reachable.
 */
@Component
public class KafkaListenerStarter {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerStarter.class);

    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean ready;
    private volatile boolean closing;

    public KafkaListenerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = true;
        startStoppedListeners();
    }

    /** Set before the listeners are stopped during shutdown, so they are not started again. */
    @EventListener(ContextClosedEvent.class)
    public void onClose() {
        closing = true;
    }

    @Scheduled(fixedDelayString = "${app.kafka.consumer.start-retry-interval:10s}",
            initialDelayString = "${app.kafka.consumer.start-retry-interval:10s}")
    public void retry() {
        if (ready) {
            startStoppedListeners();
        }
    }

    void startStoppedListeners() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (closing || container.isRunning()) {
                continue;
            }
            try {
                container.start();
                log.atInfo()
                        .setMessage("Kafka listener started")
                        .addKeyValue("action", "kafka.listener_started")
                        .addKeyValue("listenerId", container.getListenerId())
                        .log();
            } catch (RuntimeException e) {
                // A failed start can leave the container marked as running without a consumer;
                // stopping it resets that, so the next attempt starts it from scratch.
                stopQuietly(container);
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                log.atWarn()
                        .setMessage("Kafka listener could not be started; will retry")
                        .addKeyValue("action", "kafka.listener_start_failed")
                        .addKeyValue("listenerId", container.getListenerId())
                        .addKeyValue("failureReason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                        .log();
            }
        }
    }

    private static void stopQuietly(MessageListenerContainer container) {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // Nothing was running; the next attempt starts it again.
        }
    }
}
