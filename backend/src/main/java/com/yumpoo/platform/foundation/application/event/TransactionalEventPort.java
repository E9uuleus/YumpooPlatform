package com.yumpoo.platform.foundation.application.event;

public interface TransactionalEventPort {

    DomainEventEnvelope append(EventDraft draft);
}
