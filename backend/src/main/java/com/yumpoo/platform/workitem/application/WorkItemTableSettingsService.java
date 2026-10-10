package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectDeletionQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemTableSettingsModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 本人在项目中的表格展示与新建默认值；只校验结构与上限，不校验引用是否仍有效。 */
@Service
public class WorkItemTableSettingsService {
    private static final int MAX_PINNED_COLUMNS = 50;
    private static final int MAX_RULES = 20;
    private static final int MAX_VALUES = 20;
    private static final int MAX_VALUE_LENGTH = 100;
    private static final int MAX_DUE_OFFSET_DAYS = 365;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private final WorkItemTableSettingsRepository repository;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectDeletionQuery deletion;

    public WorkItemTableSettingsService(WorkItemTableSettingsRepository repository, ProjectAccessSnapshotQuery access,
            ProjectDeletionQuery deletion) {
        this.repository = repository;
        this.access = access;
        this.deletion = deletion;
    }

    @Transactional(readOnly = true)
    public View get(CurrentActor actor, UUID projectId) {
        UUID companyId = visibleCompany(actor, projectId);
        return repository.find(companyId, projectId, actor.userId())
                .map(stored -> View.of(projectId, stored.settings(), stored.updatedAt()))
                .orElseGet(() -> View.of(projectId, Write.defaults(), null));
    }

    @Transactional
    public View update(CurrentActor actor, UUID projectId, Write body, long expectedVersion) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        deletion.lockForProjection(actor.companyId(), projectId).filter(project -> project.purgeStartedAt() == null)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        UUID companyId = visibleCompany(actor, projectId);
        var stored = repository.save(companyId, projectId, actor.userId(), normalize(body), expectedVersion)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return View.of(projectId, stored.settings(), stored.updatedAt());
    }

    private UUID visibleCompany(CurrentActor actor, UUID projectId) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        return access.findVisible(actor, projectId).map(ProjectAccessSnapshot::companyId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    static Write normalize(Write w) {
        if (w == null || w.pinnedColumnCount() == null || w.pinnedColumnCount() < 0 || w.pinnedColumnCount() > MAX_PINNED_COLUMNS)
            throw invalid("pinnedColumnCount", "固定列数量需在 0–50 之间");
        if (w.headerHeight() == null || w.rowHeight() == null) throw invalid("rowHeight", "请选择表头高度与行高");
        if (w.coloringRules() == null || w.coloringRules().size() > MAX_RULES)
            throw invalid("coloringRules", "最多设置 20 条条件着色");
        Set<UUID> ids = new HashSet<>();
        for (ColoringRule rule : w.coloringRules()) {
            if (rule == null || rule.id() == null || !ids.add(rule.id()) || rule.target() == null
                    || rule.colorToken() == null || !CODE.matcher(rule.colorToken()).matches()
                    || rule.values() == null || rule.values().size() > MAX_VALUES
                    || rule.values().stream().anyMatch(value -> value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH))
                throw invalid("coloringRules", "条件着色规则无效");
        }
        DefaultValues d = w.defaultValues();
        if (d == null || d.assigneeUserIds() == null || d.assigneeUserIds().size() > MAX_VALUES
                || d.assigneeUserIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(d.assigneeUserIds()).size() != d.assigneeUserIds().size()
                || !code(d.statusCode()) || !code(d.priority())
                || d.dueDateOffsetDays() != null && (d.dueDateOffsetDays() < 0 || d.dueDateOffsetDays() > MAX_DUE_OFFSET_DAYS))
            throw invalid("defaultValues", "新建默认值无效");
        List<ColoringRule> rules = w.coloringRules().stream().map(rule -> new ColoringRule(rule.id(), rule.target(),
                rule.colorToken(), rule.column(), rule.operator(), List.copyOf(rule.values()))).toList();
        return new Write(w.pinnedColumnCount(), w.headerHeight(), w.rowHeight(), rules,
                new DefaultValues(List.copyOf(d.assigneeUserIds()), d.statusCode(), d.priority(), d.contentId(), d.dueDateOffsetDays()));
    }

    private static boolean code(String value) { return value == null || CODE.matcher(value).matches(); }

    private static ApplicationException invalid(String field, String message) {
        return ApplicationException.validation(new FieldViolation(field, "INVALID_VALUE", message));
    }
}
