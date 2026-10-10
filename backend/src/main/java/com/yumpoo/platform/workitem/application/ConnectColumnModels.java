package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ConnectColumnModels {
    private ConnectColumnModels() {}

    public record Target(UUID projectId, String code, String name, String lifecycle, boolean actorCanLinkExisting,
            boolean available) {}
    public record Column(UUID id, UUID projectId, String name, List<Target> targets,
            long rowVersion, String etag, Instant createdAt) {}
    public record IncomingColumn(UUID columnId, String columnName, UUID projectId, String projectCode, String projectName,
            String projectLifecycle, boolean actorCanLinkExisting, boolean available) {}
    public record Catalog(List<Column> items, boolean incomingAvailable, List<IncomingColumn> incomingColumns,
            boolean canManage, boolean canDelete) {}
    public record DeleteResult(UUID columnId, long removedConnectionCount) {}
}
