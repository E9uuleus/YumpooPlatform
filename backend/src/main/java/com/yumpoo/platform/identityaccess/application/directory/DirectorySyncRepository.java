package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.event.EventActor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface DirectorySyncRepository {

    DirectorySyncClaim claim(
            UUID companyId,
            DirectorySyncCommand command,
            Duration leaseDuration
    );

    void stageIdPage(
            UUID runId,
            UUID leaseToken,
            int pass,
            int pageNumber,
            String nextCursor,
            List<String> externalUserIds,
            Duration leaseDuration
    );

    void confirmScan(
            UUID runId,
            UUID leaseToken,
            DirectoryScanResult result,
            Duration leaseDuration
    );

    void stageProfile(
            UUID runId,
            UUID leaseToken,
            WeComMemberProfile profile,
            Duration leaseDuration
    );

    void beginApplying(UUID runId, UUID leaseToken, Duration leaseDuration);

    List<WeComMemberProfile> stagedProfiles(UUID runId, UUID leaseToken);

    void markApplied(
            UUID runId,
            UUID leaseToken,
            WeComMemberProfile profile,
            DirectoryMemberProvisioningResult result,
            Duration leaseDuration
    );

    DirectorySyncRunSnapshot failDuringApply(
            UUID runId,
            UUID leaseToken,
            String externalUserId,
            EventActor actor
    );

    DirectorySyncRunSnapshot fail(
            UUID runId,
            UUID leaseToken,
            String errorCode,
            String safeSummary,
            EventActor actor
    );

    DirectorySyncRunSnapshot complete(UUID runId, UUID leaseToken, EventActor actor);

    DirectorySyncRunSnapshot find(UUID runId);
}
