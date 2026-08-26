package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.LabelCatalog;
import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.PriorityLabel;
import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.StatusLabel;

@Service
public class WorkItemLabelService {
    private static final Set<String> COLORS = Set.of("GREEN", "TEAL", "BLUE", "INDIGO",
            "PURPLE", "MAGENTA", "RED", "ORANGE", "AMBER", "LIME", "CYAN", "GRAY");

    private final WorkItemLabelRepository labels;
    private final ProjectAccessSnapshotQuery access;
    private final ProjectFactWriteGuard writeGuard;
    private final Clock clock;

    public WorkItemLabelService(WorkItemLabelRepository labels,
            ProjectAccessSnapshotQuery access, ProjectFactWriteGuard writeGuard, Clock clock) {
        this.labels = labels;
        this.access = access;
        this.writeGuard = writeGuard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LabelCatalog catalog(CurrentActor actor, UUID projectId) {
        ProjectAccessSnapshot project = visible(actor, projectId);
        OptionalLong version = labels.version(project.companyId(), projectId, false);
        if (version.isEmpty()) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        return catalog(project.companyId(), projectId, version.getAsLong(), canManage(project));
    }

    @Transactional
    public LabelCatalog createStatus(CurrentActor actor, UUID projectId, long expectedVersion,
            String displayName, String colorToken) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        String name = name(displayName);
        String color = color(colorToken);
        int order = labels.statuses(project.companyId(), projectId).stream()
                .mapToInt(StatusLabel::sortOrder).max().orElse(0) + 10;
        if (!labels.insertStatus(project.companyId(), projectId, generatedCode("STATUS"), name,
                color, order, clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return finish(project, expectedVersion);
    }

    @Transactional
    public LabelCatalog createPriority(CurrentActor actor, UUID projectId, long expectedVersion,
            String displayName, String colorToken) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        String name = name(displayName);
        String color = color(colorToken);
        int order = labels.priorities(project.companyId(), projectId).stream()
                .mapToInt(PriorityLabel::sortOrder).max().orElse(0) + 10;
        if (!labels.insertPriority(project.companyId(), projectId, generatedCode("PRIORITY"),
                name, color, order, clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return finish(project, expectedVersion);
    }

    @Transactional
    public LabelCatalog updateStatus(CurrentActor actor, UUID projectId, String code,
            long expectedVersion, String displayName, String colorToken, Boolean active,
            Integer sortOrder) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        StatusLabel before = labels.statuses(project.companyId(), projectId).stream()
                .filter(label -> label.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        boolean nextActive = active == null ? before.active() : active;
        if (before.protectedLabel() && !nextActive)
            throw validation("active", "PROTECTED_LABEL", "未开始状态不能停用");
        if (!labels.updateStatus(project.companyId(), projectId, code,
                displayName == null ? before.displayName() : name(displayName),
                colorToken == null ? before.colorToken() : color(colorToken), nextActive,
                clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        if (sortOrder != null) reorderStatuses(project, code, sortOrder);
        return finish(project, expectedVersion);
    }

    @Transactional
    public LabelCatalog updatePriority(CurrentActor actor, UUID projectId, String code,
            long expectedVersion, String displayName, String colorToken, Boolean active,
            Integer sortOrder) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        PriorityLabel before = labels.priorities(project.companyId(), projectId).stream()
                .filter(label -> label.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!labels.updatePriority(project.companyId(), projectId, code,
                displayName == null ? before.displayName() : name(displayName),
                colorToken == null ? before.colorToken() : color(colorToken),
                active == null ? before.active() : active, clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        if (sortOrder != null) reorderPriorities(project, code, sortOrder);
        return finish(project, expectedVersion);
    }

    @Transactional
    public LabelCatalog deleteStatus(CurrentActor actor, UUID projectId, String code,
            long expectedVersion) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        StatusLabel before = labels.statuses(project.companyId(), projectId).stream()
                .filter(label -> label.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (before.protectedLabel())
            throw validation("code", "PROTECTED_LABEL", "未开始状态不能删除");
        if (before.inUse())
            throw validation("code", "LABEL_IN_USE", "你不能删除正在使用的标签");
        if (!labels.deleteStatus(project.companyId(), projectId, code, clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return finish(project, expectedVersion);
    }

    @Transactional
    public LabelCatalog deletePriority(CurrentActor actor, UUID projectId, String code,
            long expectedVersion) {
        ProjectFactWriteSnapshot project = writable(actor, projectId);
        lockVersion(project, expectedVersion);
        PriorityLabel before = labels.priorities(project.companyId(), projectId).stream()
                .filter(label -> label.code().equals(code)).findFirst()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (before.inUse())
            throw validation("code", "LABEL_IN_USE", "你不能删除正在使用的标签");
        if (!labels.deletePriority(project.companyId(), projectId, code, clock.instant()))
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        return finish(project, expectedVersion);
    }

    public void initialize(UUID companyId, UUID projectId, String templateKey,
            int templateVersion) {
        labels.initialize(companyId, projectId, templateKey, templateVersion, clock.instant());
    }

    private LabelCatalog finish(ProjectFactWriteSnapshot project, long expectedVersion) {
        Instant now = clock.instant();
        labels.incrementVersion(project.companyId(), project.projectId(), expectedVersion, now);
        return catalog(project.companyId(), project.projectId(), expectedVersion + 1, true);
    }

    private void reorderStatuses(ProjectFactWriteSnapshot project, String code, int requestedOrder) {
        List<StatusLabel> ordered = new ArrayList<>(labels.statuses(
                project.companyId(), project.projectId()));
        reorder(ordered, code, requestedOrder, StatusLabel::code, StatusLabel::sortOrder);
        Map<String, Integer> orders = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++)
            orders.put(ordered.get(index).code(), (index + 1) * 10);
        labels.rewriteStatusOrders(project.companyId(), project.projectId(), orders, clock.instant());
    }

    private void reorderPriorities(ProjectFactWriteSnapshot project, String code,
            int requestedOrder) {
        List<PriorityLabel> ordered = new ArrayList<>(labels.priorities(
                project.companyId(), project.projectId()));
        reorder(ordered, code, requestedOrder, PriorityLabel::code, PriorityLabel::sortOrder);
        Map<String, Integer> orders = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++)
            orders.put(ordered.get(index).code(), (index + 1) * 10);
        labels.rewritePriorityOrders(project.companyId(), project.projectId(), orders, clock.instant());
    }

    private static <T> void reorder(List<T> ordered, String code, int requestedOrder,
            java.util.function.Function<T, String> codeOf,
            java.util.function.ToIntFunction<T> orderOf) {
        T moving = ordered.stream().filter(value -> codeOf.apply(value).equals(code))
                .findFirst().orElseThrow(() -> new ApplicationException(
                        StandardErrorCode.RESOURCE_NOT_FOUND));
        ordered.remove(moving);
        int index = 0;
        while (index < ordered.size() && orderOf.applyAsInt(ordered.get(index)) < requestedOrder)
            index++;
        ordered.add(index, moving);
    }

    private LabelCatalog catalog(UUID companyId, UUID projectId, long version, boolean canManage) {
        return new LabelCatalog(labels.statuses(companyId, projectId),
                labels.priorities(companyId, projectId), version, StrongEtag.format(version), canManage);
    }

    private ProjectAccessSnapshot visible(CurrentActor actor, UUID projectId) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        return access.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private ProjectFactWriteSnapshot writable(CurrentActor actor, UUID projectId) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        ProjectFactWriteSnapshot project = writeGuard.lockForFactWrite(actor, projectId);
        if (project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() == ProjectFactWriteSnapshot.ProjectLifecycle.ARCHIVED)
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "PROJECT_ARCHIVED");
        return project;
    }

    private void lockVersion(ProjectFactWriteSnapshot project, long expectedVersion) {
        OptionalLong current = labels.version(project.companyId(), project.projectId(), true);
        if (current.isEmpty()) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        if (current.getAsLong() != expectedVersion)
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }

    private static boolean canManage(ProjectAccessSnapshot project) {
        return project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED
                && project.actorAccess() != ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY;
    }

    private static String generatedCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String name(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 80)
            throw validation("displayName", "INVALID_LENGTH", "标签名称必须为 1 到 80 个字符");
        return value.strip();
    }

    private static String color(String value) {
        String normalized = value == null ? null : value.strip().toUpperCase(Locale.ROOT);
        if (!COLORS.contains(normalized))
            throw validation("colorToken", "INVALID_COLOR", "标签颜色不在允许的色板中");
        return normalized;
    }

    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
