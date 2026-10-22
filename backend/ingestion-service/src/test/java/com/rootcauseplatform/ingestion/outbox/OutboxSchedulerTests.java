package com.rootcauseplatform.ingestion.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(OutputCaptureExtension.class)
class OutboxSchedulerTests {

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final OutboxProperties properties = new OutboxProperties(true, Duration.ofSeconds(1), 2,
            Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(60), 10, Duration.ofDays(7));
    private final OutboxScheduler scheduler = new OutboxScheduler(publisher, mock(OutboxEventRepository.class),
            properties);

    @Test
    void keepsPublishingWhileBatchesAreFull() {
        when(publisher.publishDueBatch()).thenReturn(2, 2, 1);

        scheduler.publishPendingMessages();

        verify(publisher, times(3)).publishDueBatch();
    }

    @Test
    void logsAnUnreachableDatabaseAsAWarningWithoutAStackTrace(CapturedOutput output) {
        when(publisher.publishDueBatch()).thenThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new IllegalStateException("Connection is not available")));

        assertThatCode(scheduler::publishPendingMessages).doesNotThrowAnyException();

        assertThat(output.getOut())
                .contains("Outbox publisher cannot reach the database; will retry")
                .doesNotContain("\tat ");
    }

    @Test
    void leavesUnexpectedErrorsToTheDefaultErrorHandling() {
        when(publisher.publishDueBatch()).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(scheduler::publishPendingMessages).isInstanceOf(IllegalStateException.class);
    }
}
