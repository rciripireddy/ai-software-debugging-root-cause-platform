package com.rootcauseplatform.incident.messaging;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class KafkaListenerStarterTests {

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final KafkaListenerStarter starter = new KafkaListenerStarter(registry);

    KafkaListenerStarterTests() {
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(container.getListenerId()).thenReturn("error-event-consumer");
    }

    @Test
    void retriesUntilTheConsumerCanBeCreated() {
        // What Kafka throws while the broker's host name cannot be resolved.
        doThrow(new KafkaException("Failed to construct kafka consumer",
                new ConfigException("No resolvable bootstrap urls given in bootstrap.servers")))
                .doNothing()
                .when(container).start();

        starter.onReady();
        starter.retry();

        verify(container, times(2)).start();
        // The failed attempt is reset, otherwise the container would look running without a consumer.
        verify(container, times(1)).stop();
    }

    @Test
    void doesNothingBeforeTheApplicationIsReadyOrForRunningListeners() {
        starter.retry();
        verify(container, never()).start();

        when(container.isRunning()).thenReturn(true);
        starter.onReady();
        verify(container, never()).start();
    }

    @Test
    void doesNotStartListenersWhileTheApplicationShutsDown() {
        starter.onReady();
        verify(container, times(1)).start();

        starter.onClose();
        starter.retry();

        verify(container, times(1)).start();
    }
}
