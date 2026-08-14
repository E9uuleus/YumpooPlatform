package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Optional;
import java.util.UUID;

/**
 * M2 persistence adapters must constrain company and visibility in SQL. Implementations must not
 * load an unrestricted project and hide it afterwards in Java.
 */
public interface ProjectAccessSnapshotQuery {

    Optional<ProjectAccessSnapshot> findVisible(CurrentActor actor, UUID projectId);
}
