package com.yumpoo.platform.foundation.application.outbox;
import java.time.Instant;
public interface OutboxBacklogPort {
    Backlog read();
    record Backlog(long pending, long processing, long retry, long dead, Instant oldest) {
        public long total() { return pending + processing + retry; }
    }
}
