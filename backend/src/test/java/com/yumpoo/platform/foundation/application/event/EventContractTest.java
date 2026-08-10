package com.yumpoo.platform.foundation.application.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventContractTest {

    @Test
    void actorVariantsEnforceTheirMutuallyExclusiveFields() {
        UUID userId = UUID.randomUUID();

        assertThat(EventActor.user(userId).type()).isEqualTo(EventActorType.USER);
        assertThat(EventActor.system("OUTBOX.JOB").type()).isEqualTo(EventActorType.SYSTEM);
        assertThat(EventActor.adminOverride(userId, "override-123").reasonReference())
                .isEqualTo("override-123");

        assertThatThrownBy(() -> new EventActor(EventActorType.USER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventActor.system("unsafe code"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventActor.adminOverride(userId, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeRejectsUnsafeNamesVersionsAndNonObjectPayloads() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new EventDraft(
                "ProbeRecorded",
                1,
                "Probe",
                id,
                0,
                UUID.randomUUID(),
                EventActor.user(UUID.randomUUID()),
                JsonNodeFactory.instance.objectNode()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new DomainEventEnvelope(
                UUID.randomUUID(),
                "foundation.probe_recorded",
                0,
                Instant.now(),
                "Probe",
                id,
                0,
                UUID.randomUUID(),
                EventActor.user(UUID.randomUUID()),
                "request-1",
                "request-1",
                null,
                JsonNodeFactory.instance.arrayNode()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new DomainEventEnvelope(
                UUID.fromString("00000000-0000-3000-8000-000000000001"),
                "foundation.probe_recorded",
                1,
                Instant.now(),
                "Probe",
                id,
                0,
                UUID.randomUUID(),
                EventActor.user(UUID.randomUUID()),
                "request-1",
                "request-1",
                null,
                JsonNodeFactory.instance.objectNode()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eventId must be UUIDv4");
    }
}
