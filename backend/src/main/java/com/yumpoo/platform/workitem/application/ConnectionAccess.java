package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

final class ConnectionAccess {
    private ConnectionAccess() {}

    static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    static boolean member(ProjectAccessSnapshot project) {
        return project != null && project.actorAccess().membershipBacked();
    }

    static boolean writable(ProjectAccessSnapshot project) {
        return member(project) && project.lifecycle() == ProjectAccessSnapshot.ProjectLifecycle.ACTIVE;
    }

    static void requireWritable(ProjectAccessSnapshot project) {
        if (!member(project)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ACTIVE) throw conflict("PROJECT_ARCHIVED");
    }

    static void requireWritable(ProjectFactWriteSnapshot project) {
        if (project.actorAccess() == ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY)
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        if (project.lifecycle() != ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE) throw conflict("PROJECT_ARCHIVED");
    }

    static ApplicationException missing() { return new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND); }
    static ApplicationException conflict(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }
    static ApplicationException invalid(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
    static void requireVersion(long actual, long expected) {
        if (actual != expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
    }
}
