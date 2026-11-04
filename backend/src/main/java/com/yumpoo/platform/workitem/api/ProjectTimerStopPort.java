package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.UUID;

public interface ProjectTimerStopPort {
    /** Must be called in the transaction that already holds the project's archive or end lock. */
    int stopRunningTimers(CurrentActor actor, UUID projectId);

    /** Must be called in the leave transaction after the project lock; stops only the leaving member's timers in this project. */
    int stopLeavingMemberTimers(CurrentActor actor, UUID projectId);
}
