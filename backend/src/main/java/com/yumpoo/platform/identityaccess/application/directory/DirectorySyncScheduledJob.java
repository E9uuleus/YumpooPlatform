package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJob;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobOutcome;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DirectorySyncScheduledJob implements ScheduledJob {
    private final ObjectProvider<DirectorySyncAdministrationUseCase> provider;

    public DirectorySyncScheduledJob(ObjectProvider<DirectorySyncAdministrationUseCase> provider) {
        this.provider = provider;
    }

    @Override
    public String code() { return "DIRECTORY_SYNC"; }

    @Override
    public boolean available() { return provider.getIfAvailable() != null; }

    @Override
    public ScheduledJobResult run(UUID companyId, String triggerKey, String requestId) {
        DirectorySyncAdministrationUseCase useCase = provider.getIfAvailable();
        if (useCase == null) return new ScheduledJobResult(ScheduledJobOutcome.SKIPPED, "DIRECTORY_SYNC_DISABLED");
        DirectorySyncExecutionResult execution = useCase.executeWithDisposition(new DirectorySyncCommand(
                triggerKey, DirectorySyncTriggerType.SCHEDULED, EventActor.system("DIRECTORY_SYNC_SCHEDULER"), requestId));
        if (execution.disposition() != DirectorySyncClaimDisposition.NEW) {
            return new ScheduledJobResult(ScheduledJobOutcome.SKIPPED, "DIRECTORY_SYNC_" + execution.disposition());
        }
        DirectorySyncRunSnapshot run = execution.snapshot();
        return switch (run.status()) {
            case SUCCEEDED -> ScheduledJobResult.succeeded();
            case PARTIALLY_SUCCEEDED -> new ScheduledJobResult(ScheduledJobOutcome.PARTIAL, run.errorCode());
            case FAILED -> new ScheduledJobResult(ScheduledJobOutcome.FAILED, run.errorCode());
            case RUNNING -> new ScheduledJobResult(ScheduledJobOutcome.SKIPPED, "DIRECTORY_SYNC_STILL_RUNNING");
        };
    }
}
