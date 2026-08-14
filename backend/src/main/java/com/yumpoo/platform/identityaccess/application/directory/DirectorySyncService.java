package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "yumpoo.wecom.directory", name = "enabled", havingValue = "true")
public class DirectorySyncService implements DirectorySyncUseCase, DirectorySyncAdministrationUseCase {

    private final CompanyConfigurationQuery companyQuery;
    private final DirectorySyncRepository repository;
    private final FullDirectoryScanCollector scanCollector;
    private final WeComDirectoryProfileGateway profileGateway;
    private final DirectorySyncItemApplyService itemApplyService;
    private final DirectorySyncSettings settings;

    public DirectorySyncService(
            CompanyConfigurationQuery companyQuery,
            DirectorySyncRepository repository,
            FullDirectoryScanCollector scanCollector,
            WeComDirectoryProfileGateway profileGateway,
            DirectorySyncItemApplyService itemApplyService,
            DirectorySyncSettings settings
    ) {
        this.companyQuery = Objects.requireNonNull(companyQuery, "companyQuery must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.scanCollector = Objects.requireNonNull(scanCollector, "scanCollector must not be null");
        this.profileGateway = Objects.requireNonNull(profileGateway, "profileGateway must not be null");
        this.itemApplyService = Objects.requireNonNull(
                itemApplyService,
                "itemApplyService must not be null"
        );
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public DirectorySyncRunSnapshot execute(DirectorySyncCommand command) {
        return executeWithDisposition(command).snapshot();
    }

    @Override
    public DirectorySyncExecutionResult executeWithDisposition(DirectorySyncCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(command.requestId())
        )) {
            UUID companyId = companyQuery.current().companyId();
            DirectorySyncClaim claim = repository.claim(companyId, command, settings.leaseDuration());
            if (!claim.executionOwner()) {
                return new DirectorySyncExecutionResult(claim.snapshot(), claim.disposition());
            }
            UUID runId = claim.snapshot().runId();
            UUID leaseToken = claim.leaseToken();
            try {
                return created(executeOwned(command, runId, leaseToken));
            } catch (DirectorySyncLeaseLostException exception) {
                return created(repository.find(runId));
            } catch (DirectorySyncException exception) {
                return created(safeFail(runId, leaseToken, exception, command));
            } catch (RuntimeException exception) {
                return created(safeFail(
                        runId,
                        leaseToken,
                        new DirectorySyncException(
                                "DIRECTORY_SYNC_UNEXPECTED_FAILURE",
                                "The directory synchronization failed closed"
                        ),
                        command
                ));
            }
        }
    }

    private static DirectorySyncExecutionResult created(DirectorySyncRunSnapshot snapshot) {
        return new DirectorySyncExecutionResult(snapshot, DirectorySyncClaimDisposition.NEW);
    }

    private DirectorySyncRunSnapshot executeOwned(
            DirectorySyncCommand command,
            UUID runId,
            UUID leaseToken
    ) {
        DirectoryScanResult scan = scanCollector.collect((pass, pageNumber, nextCursor, members) ->
                repository.stageIdPage(
                        runId,
                        leaseToken,
                        pass,
                        pageNumber,
                        nextCursor,
                        members,
                        settings.leaseDuration()
                )
        );
        repository.confirmScan(runId, leaseToken, scan, settings.leaseDuration());
        if (scan.externalUserIds().isEmpty() && repository.hasActiveDirectoryMembers(
                companyQuery.current().companyId()
        )) {
            throw new DirectorySyncException(
                    "DIRECTORY_EMPTY_SNAPSHOT_REJECTED",
                    "An empty provider snapshot was rejected while active members exist"
            );
        }

        Map<Long, String> departments;
        try {
            departments = profileGateway.fetchDepartmentNames();
        } catch (DirectorySyncException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DirectorySyncException(
                    "DIRECTORY_DEPARTMENT_PROVIDER_FAILED",
                    "The provider department dictionary could not be read"
            );
        }

        for (String externalUserId : scan.externalUserIds()) {
            WeComMemberProfile profile;
            try {
                WeComRawMemberProfile raw = profileGateway.fetchMemberProfile(externalUserId);
                if (!externalUserId.equals(raw.externalUserId())) {
                    throw new DirectorySyncException(
                            "DIRECTORY_PROFILE_ID_MISMATCH",
                            "A provider profile did not match the requested member identifier",
                            DirectorySyncFailureScope.ITEM_ISOLATABLE
                    );
                }
                profile = DirectoryProfileMapper.map(raw, departments);
            } catch (DirectorySyncException exception) {
                if (exception.scope() == DirectorySyncFailureScope.RUN_FATAL) {
                    throw exception;
                }
                repository.markProfileFailed(
                        runId,
                        leaseToken,
                        externalUserId,
                        exception.errorCode(),
                        settings.leaseDuration()
                );
                continue;
            } catch (RuntimeException exception) {
                repository.markProfileFailed(
                        runId,
                        leaseToken,
                        externalUserId,
                        "DIRECTORY_PROFILE_MAPPING_FAILED",
                        settings.leaseDuration()
                );
                continue;
            }
            repository.stageProfile(
                    runId,
                    leaseToken,
                    profile,
                    settings.leaseDuration()
            );
        }

        repository.beginApplying(runId, leaseToken, settings.leaseDuration());
        for (WeComMemberProfile profile : repository.stagedProfiles(runId, leaseToken)) {
            try {
                itemApplyService.apply(
                        runId,
                        leaseToken,
                        profile,
                        command.actor(),
                        settings.leaseDuration()
                );
            } catch (DirectorySyncLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                repository.markApplyFailed(
                        runId,
                        leaseToken,
                        profile.externalUserId(),
                        settings.leaseDuration()
                );
            }
        }
        if (repository.hasItemFailures(runId, leaseToken)) {
            return repository.completePartial(runId, leaseToken, command.actor());
        }
        return repository.complete(runId, leaseToken, command.actor());
    }

    private DirectorySyncRunSnapshot safeFail(
            UUID runId,
            UUID leaseToken,
            DirectorySyncException failure,
            DirectorySyncCommand command
    ) {
        try {
            return repository.fail(
                    runId,
                    leaseToken,
                    failure.errorCode(),
                    failure.safeSummary(),
                    command.actor()
            );
        } catch (DirectorySyncLeaseLostException exception) {
            return repository.find(runId);
        }
    }
}
