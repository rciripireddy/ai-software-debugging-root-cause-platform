package com.rootcauseplatform.incident.messaging;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Topics and failure handling for the error-event consumer.
 *
 * <p>A message whose processing throws is retried with exponential backoff, up to
 * {@code app.kafka.consumer.retry.max-retries} times. If it still fails, or if it is invalid
 * ({@link InvalidEventException}, never retried), it is published unchanged to the dead-letter
 * topic with Spring Kafka's exception headers, and the consumer moves on to the next message.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);

    @Bean
    NewTopic errorEventsTopic(KafkaTopicProperties topics) {
        return TopicBuilder.name(topics.errorEvents()).partitions(topics.partitions()).build();
    }

    @Bean
    NewTopic errorEventsDeadLetterTopic(KafkaTopicProperties topics) {
        return TopicBuilder.name(topics.errorEventsDlt()).partitions(topics.partitions()).build();
    }

    @Bean
    DefaultErrorHandler errorEventErrorHandler(KafkaOperations<String, String> kafka, KafkaTopicProperties topics,
            ConsumerRetryProperties retry) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(topics.errorEventsDlt(), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxRetries());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            deadLetters.accept(record, exception);
            logFailure(log.atError(), "Message sent to dead-letter topic", record, exception)
                    .addKeyValue("action", "event.dead_letter")
                    .addKeyValue("deadLetterTopic", topics.errorEventsDlt())
                    .log();
        }, backOff);
        errorHandler.addNotRetryableExceptions(InvalidEventException.class);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                logFailure(log.atWarn(), "Message processing failed", record, exception)
                        .addKeyValue("action", "event.process_failed")
                        .addKeyValue("deliveryAttempt", deliveryAttempt)
                        .log());
        // The structured log lines above replace the framework's own logging and stack traces.
        errorHandler.setLogLevel(KafkaException.Level.DEBUG);
        return errorHandler;
    }

    private static LoggingEventBuilder logFailure(LoggingEventBuilder builder, String message,
            ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        return builder.setMessage(message)
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("messageKey", record.key())
                .addKeyValue("correlationId", header(record, EventHeaders.CORRELATION_ID))
                .addKeyValue("failureReason", cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
