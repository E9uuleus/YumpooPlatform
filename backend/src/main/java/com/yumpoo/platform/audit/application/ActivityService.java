package com.yumpoo.platform.audit.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

@Service
public class ActivityService {
    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;

    private final ActivityRepository repository;
    private final ActivityCursorCodec cursors;

    public ActivityService(ActivityRepository repository, ActivityCursorCodec cursors) {
        this.repository = repository;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    public ActivityResultPage findProject(UUID companyId, UUID projectId,
            ActivityQueryCriteria query) {
        return find(companyId, projectId, null, query);
    }

    @Transactional(readOnly = true)
    public ActivityResultPage findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            ActivityQueryCriteria query) {
        return find(companyId, projectId, workItemId, query);
    }

    private ActivityResultPage find(UUID companyId, UUID projectId, UUID workItemId,
            ActivityQueryCriteria query) {
        String scope = companyId + "|" + projectId + "|" + workItemId;
        return page(scope, query, (anchor, limit) -> workItemId == null
                ? repository.findScope(companyId, "PROJECT", projectId,
                        query.eventTypes(), query.entityTypes(), query.occurredFrom(), query.occurredTo(),
                        anchor, limit)
                : repository.findWorkItem(companyId, projectId, workItemId, query.eventTypes(),
                        query.entityTypes(), query.occurredFrom(), query.occurredTo(), anchor, limit));
    }

    @Transactional(readOnly = true)
    public ActivityResultPage findCompany(UUID companyId, Set<UUID> projectIds, Set<UUID> actorUserIds,
            ActivityQueryCriteria query) {
        String scope = companyId + "|company|" + sorted(projectIds) + '|' + sorted(actorUserIds);
        return page(scope, query, (anchor, limit) -> repository.findCompany(companyId, projectIds, actorUserIds,
                query.eventTypes(), query.occurredFrom(), query.occurredTo(), anchor, limit));
    }

    private ActivityResultPage page(String scope, ActivityQueryCriteria query,
            BiFunction<ActivityRepository.CursorAnchor, Integer, List<ActivityStoredEvent>> fetch) {
        int size = query.size() == null ? DEFAULT_SIZE : query.size();
        if (size < 1 || size > MAX_SIZE) throw validation("size", "INVALID_PAGE_SIZE",
                "size 必须在 1 到 100 之间");
        if (query.occurredFrom() != null && query.occurredTo() != null
                && query.occurredFrom().isAfter(query.occurredTo())) {
            throw validation("occurredFrom", "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
        Set<String> eventTypes = query.eventTypes();
        Set<String> entityTypes = query.entityTypes();
        String fingerprint = fingerprint(scope, eventTypes,
                entityTypes, query.occurredFrom(), query.occurredTo());
        ActivityCursorCodec.Cursor cursor = cursors.decode(query.cursor());
        if (cursor != null && !fingerprint.equals(cursor.fingerprint())) {
            throw ActivityCursorCodec.invalidCursor();
        }
        List<ActivityStoredEvent> rows = new ArrayList<>(fetch.apply(
                cursor == null ? null : cursor.anchor(), size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.removeLast();
        String nextCursor = hasMore && !rows.isEmpty()
                ? cursors.encode(new ActivityCursorCodec.Cursor(fingerprint,
                        new ActivityRepository.CursorAnchor(rows.getLast().occurredAt(),
                                rows.getLast().id()))) : null;
        return new ActivityResultPage(rows, nextCursor, repository.acceptedFrom());
    }

    private static String fingerprint(String scope,
            Set<String> events, Set<String> entities, Instant from, Instant to) {
        String raw = "v1|" + scope + '|'
                + events.stream().sorted().toList() + '|' + entities.stream().sorted().toList()
                + '|' + from + '|' + to;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<UUID> sorted(Set<UUID> values) {
        return values.stream().sorted().toList();
    }

    private static ApplicationException validation(String field, String code, String message) {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new FieldViolation(field, code, message)));
    }
}
