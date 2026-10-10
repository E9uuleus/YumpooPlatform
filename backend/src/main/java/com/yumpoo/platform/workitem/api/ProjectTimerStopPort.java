package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.UUID;

public interface ProjectTimerStopPort {
    /** Must be called in the transaction that already holds the project's archive lock. */
    int stopRunningTimers(CurrentActor actor, UUID projectId);
}
