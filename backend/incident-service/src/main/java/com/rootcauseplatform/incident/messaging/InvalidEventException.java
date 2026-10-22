package com.rootcauseplatform.incident.messaging;

/**
 * A Kafka message that can never be processed: malformed JSON, an unsupported type or
 * version, or missing/invalid fields. Such messages are not retried; they go straight to the
 * dead-letter topic. The message text never contains message content.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
