package com.rootcauseplatform.ingestion.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks the oldest due, unpublished messages. {@code SKIP LOCKED} lets several service
     * instances publish concurrently without sending the same message twice at the same time.
     */
    @Query(nativeQuery = true, value = """
            SELECT * FROM ingestion.outbox_events
            WHERE published_at IS NULL AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""")
    List<OutboxEvent> lockDueBatch(Instant now, int limit);

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
}
