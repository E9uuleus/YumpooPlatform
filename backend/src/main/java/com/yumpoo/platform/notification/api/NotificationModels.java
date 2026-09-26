package com.yumpoo.platform.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationModels {
    private NotificationModels() {}
    public enum Reason { MENTION, REPLY, COMMENT, ASSIGNED, PROJECT_MEMBER_ADDED,
        PROJECT_MEMBER_REMOVED, PROJECT_OWNER_ASSIGNED, PROJECT_OWNER_TRANSFERRED }
    public enum State { UNREAD, READ, ARCHIVED }
    public enum ListState { UNREAD, ALL, ARCHIVED }
    public enum Group { MENTION, COMMENT, ASSIGNED, PROJECT }
    public enum TargetKind { PROJECT, WORK_ITEM, WORK_ITEM_UPDATE }
    public record Person(UUID id, String displayName) {}
    public record Target(TargetKind kind, boolean accessible, UUID projectId, String projectName,
            UUID workItemId, String itemNo, String title, UUID updateId, String excerpt) {
        public static Target inaccessible(TargetKind kind) {
            return new Target(kind, false, null, null, null, null, null, null, null);
        }
    }
    public record Item(UUID id, Reason reason, State state, Instant createdAt, Instant readAt,
            Person actor, Person subject, Target target) {}
    public record Page(List<Item> items, String nextCursor, Instant serverNow) {}
    public record UnreadCounts(long total, long mention, long comment, long assigned, long project,
            Instant newestUnreadAt, Instant serverNow) {}
}
