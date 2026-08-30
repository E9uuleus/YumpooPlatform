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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        int size = query.size() == null ? DEFAULT_SIZE : query.size();
        if (size < 1 || size > MAX_SIZE) throw validation("size", "INVALID_PAGE_SIZE",
                "size 必须在 1 到 100 之间");
        if (query.occurredFrom() != null && query.occurredTo() != null
                && query.occurredFrom().isAfter(query.occurredTo())) {
            throw validation("occurredFrom", "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
        Set<String> eventTypes = query.eventTypes();
        Set<String> entityTypes = query.entityTypes();
        String fingerprint = fingerprint(companyId, projectId, workItemId, eventTypes,
                entityTypes, query.occurredFrom(), query.occurredTo());
        ActivityCursorCodec.Cursor cursor = cursors.decode(query.cursor());
        if (cursor != null && !fingerprint.equals(cursor.fingerprint())) {
            throw ActivityCursorCodec.invalidCursor();
        }
        List<ActivityStoredEvent> rows = new ArrayList<>(workItemId == null
                ? repository.findScope(companyId, "PROJECT", projectId,
                        eventTypes, entityTypes, query.occurredFrom(), query.occurredTo(),
                        cursor == null ? null : cursor.anchor(), size + 1)
                : repository.findWorkItem(companyId, projectId, workItemId, eventTypes,
                        entityTypes, query.occurredFrom(), query.occurredTo(),
                        cursor == null ? null : cursor.anchor(), size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.removeLast();
        String nextCursor = hasMore && !rows.isEmpty()
                ? cursors.encode(new ActivityCursorCodec.Cursor(fingerprint,
                        new ActivityRepository.CursorAnchor(rows.getLast().occurredAt(),
                                rows.getLast().id()))) : null;
        return new ActivityResultPage(rows, nextCursor, repository.acceptedFrom());
    }

    private static String fingerprint(UUID companyId, UUID projectId, UUID workItemId,
            Set<String> events, Set<String> entities, Instant from, Instant to) {
        String raw = "v1|" + companyId + '|' + projectId + '|' + workItemId + '|'
                + events.stream().sorted().toList() + '|' + entities.stream().sorted().toList()
                + '|' + from + '|' + to;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ApplicationException validation(String field, String code, String message) {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new FieldViolation(field, code, message)));
    }
}
