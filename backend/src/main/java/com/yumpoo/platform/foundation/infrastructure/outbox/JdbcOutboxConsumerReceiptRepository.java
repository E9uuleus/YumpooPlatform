package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerReceiptPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcOutboxConsumerReceiptRepository implements OutboxConsumerReceiptPort {

    private static final String TRY_INSERT = """
            INSERT INTO yumpoo.outbox_consumer_receipt (
                consumer_name,
                event_id,
                completed_at
            ) VALUES (
                :consumerName,
                :eventId,
                :completedAt
            )
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """;

    private final JdbcClient jdbcClient;

    public JdbcOutboxConsumerReceiptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean tryBegin(String consumerName, UUID eventId, Instant completedAt) {
        return jdbcClient.sql(TRY_INSERT)
                .param("consumerName", consumerName)
                .param("eventId", eventId)
                .param("completedAt", OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC))
                .update() == 1;
    }
}
