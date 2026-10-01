package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ConnectColumn(UUID id, UUID companyId, UUID projectId, String name,
        List<UUID> targetProjectIds, long rowVersion, UUID createdByUserId, Instant createdAt,
        UUID updatedByUserId, Instant updatedAt, UUID deletedByUserId, Instant deletedAt) {
    public static final int MAX_COLUMNS = 20;
    public static final int MAX_TARGETS = 20;
    private static final Set<String> RESERVED_NAMES = Set.of("工作项名称", "处理人", "状态", "优先级",
            "工作项类别", "截止日期", "时长追踪", "最后更新时间", "被连接");

    public ConnectColumn {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companyId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(createdByUserId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedByUserId);
        Objects.requireNonNull(updatedAt);
        name = normalizeName(name);
        targetProjectIds = normalizeTargets(projectId, targetProjectIds);
        if (rowVersion < 0 || updatedAt.isBefore(createdAt)
                || (deletedAt != null && deletedAt.isBefore(createdAt)))
            throw new IllegalArgumentException("invalid version or timestamps");
        if ((deletedAt == null) != (deletedByUserId == null))
            throw new IllegalArgumentException("incomplete deletion facts");
    }

    public static String normalizeName(String name) {
        String value = Objects.requireNonNull(name).strip();
        if (value.isEmpty() || value.length() > 40 || RESERVED_NAMES.contains(value))
            throw new IllegalArgumentException("连接列名称须为 1–40 个字符，且不能使用内置列名称");
        return value;
    }

    public static List<UUID> normalizeTargets(UUID projectId, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_TARGETS || ids.stream().anyMatch(Objects::isNull)
                || ids.contains(projectId))
            throw new IllegalArgumentException("连接列须包含 1–20 个其他项目");
        return ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    public boolean active() { return deletedAt == null; }

    public static ConnectColumn create(UUID id, UUID companyId, UUID projectId, String name,
            List<UUID> targets, UUID actor, Instant now) {
        return new ConnectColumn(id, companyId, projectId, name, targets, 0, actor, now, actor, now, null, null);
    }

    public ConnectColumn update(String nextName, List<UUID> nextTargets, UUID actor, Instant now) {
        if (!active()) throw new IllegalStateException("column is deleted");
        return new ConnectColumn(id, companyId, projectId, nextName, nextTargets, rowVersion + 1,
                createdByUserId, createdAt, actor, now, null, null);
    }

    public ConnectColumn delete(UUID actor, Instant now) {
        if (!active()) throw new IllegalStateException("column is deleted");
        return new ConnectColumn(id, companyId, projectId, name, targetProjectIds, rowVersion + 1,
                createdByUserId, createdAt, actor, now, actor, now);
    }
}
