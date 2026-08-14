package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncAdministrationUseCase;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncCommand;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncExecutionResult;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySyncTriggerType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ManualDirectorySyncService {

    private final IdentityAdminAccessPolicy accessPolicy;
    private final ObjectProvider<DirectorySyncAdministrationUseCase> useCaseProvider;

    public ManualDirectorySyncService(
            IdentityAdminAccessPolicy accessPolicy,
            ObjectProvider<DirectorySyncAdministrationUseCase> useCaseProvider
    ) {
        this.accessPolicy = accessPolicy;
        this.useCaseProvider = useCaseProvider;
    }

    public DirectorySyncExecutionResult execute(
            UUID companyId,
            UUID actorUserId,
            String idempotencyKey,
            String requestId
    ) {
        accessPolicy.requireCompanyAdmin(companyId, actorUserId);
        DirectorySyncAdministrationUseCase useCase = useCaseProvider.getIfAvailable();
        if (useCase == null) {
            throw new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return useCase.executeWithDisposition(new DirectorySyncCommand(
                idempotencyKey,
                DirectorySyncTriggerType.MANUAL,
                EventActor.user(actorUserId),
                requestId
        ));
    }
}
