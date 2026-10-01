package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.ConnectColumn;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectColumnRepository {
    record IncomingColumn(UUID columnId, String columnName, UUID projectId) {}

    long lockCatalog(UUID companyId, UUID projectId, Instant now);
    void bumpCatalog(UUID companyId, UUID projectId, Instant now);
    List<ConnectColumn> findActive(UUID companyId, UUID projectId);
    List<IncomingColumn> findIncoming(UUID companyId, UUID projectId);
    Optional<ConnectColumn> find(UUID companyId, UUID projectId, UUID columnId);
    Optional<ConnectColumn> lock(UUID companyId, UUID projectId, UUID columnId, boolean forShare);
    long countActive(UUID companyId, UUID projectId);
    boolean nameExists(UUID companyId, UUID projectId, String name, UUID excludingId);
    boolean insert(ConnectColumn column);
    boolean update(ConnectColumn column, long expectedVersion);
    void replaceTargets(ConnectColumn column);
}
