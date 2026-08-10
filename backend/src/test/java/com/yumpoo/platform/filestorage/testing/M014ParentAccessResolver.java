package com.yumpoo.platform.filestorage.testing;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M0-14 探针专用父对象权限桩；正式父对象授权仍由 M2 的调用模块负责。
 */
public final class M014ParentAccessResolver {

    private final ConcurrentHashMap<UUID, Set<UUID>> readers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> writers = new ConcurrentHashMap<>();

    public void grantOwner(UUID ownerId, UUID actorId) {
        grantRead(ownerId, actorId);
        grantWrite(ownerId, actorId);
    }

    public void grantRead(UUID ownerId, UUID actorId) {
        readers.computeIfAbsent(ownerId, ignored -> ConcurrentHashMap.newKeySet()).add(actorId);
    }

    public void grantWrite(UUID ownerId, UUID actorId) {
        writers.computeIfAbsent(ownerId, ignored -> ConcurrentHashMap.newKeySet()).add(actorId);
    }

    public void revokeRead(UUID ownerId, UUID actorId) {
        remove(readers, ownerId, actorId);
    }

    public void revokeWrite(UUID ownerId, UUID actorId) {
        remove(writers, ownerId, actorId);
    }

    public boolean canRead(UUID ownerId, UUID actorId) {
        return readers.getOrDefault(ownerId, Set.of()).contains(actorId);
    }

    public boolean canWrite(UUID ownerId, UUID actorId) {
        return writers.getOrDefault(ownerId, Set.of()).contains(actorId);
    }

    public void reset() {
        readers.clear();
        writers.clear();
    }

    private static void remove(
            ConcurrentHashMap<UUID, Set<UUID>> access,
            UUID ownerId,
            UUID actorId
    ) {
        Set<UUID> actors = access.get(ownerId);
        if (actors != null) {
            actors.remove(actorId);
        }
    }
}
