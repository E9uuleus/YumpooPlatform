package com.yumpoo.platform.identityaccess.testing;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M0-13 隔离探针编排：完整页集才进入成员对账，逐成员失败保留部分成功事实。
 */
public class M013DirectoryReconciliationService {

    public static final String ITEM_FAILURE_CODE = "M013_ITEM_RECONCILIATION_FAILED";

    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final M013DirectoryProbeRepository repository;

    public M013DirectoryReconciliationService(M013DirectoryProbeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public M013DirectoryProbeRepository.RunRow synchronize(
            Set<String> discoveredFingerprints,
            boolean scanComplete,
            Set<String> failedFingerprints
    ) {
        Set<String> discovered = validatedFingerprints(
                discoveredFingerprints,
                "discoveredFingerprints"
        );
        Set<String> failed = validatedFingerprints(failedFingerprints, "failedFingerprints");
        if (!discovered.containsAll(failed)) {
            throw new IllegalArgumentException(
                    "failedFingerprints must be a subset of discoveredFingerprints"
            );
        }

        UUID runId = repository.startRun();
        repository.stageSeen(runId, discovered);

        if (!scanComplete) {
            return repository.finishRun(runId, "FAILED", false, 0);
        }

        for (String memberFingerprint : discovered.stream().sorted().toList()) {
            if (failed.contains(memberFingerprint)) {
                reconcileWithInjectedFailure(runId, memberFingerprint);
            } else {
                repository.reconcileSeenMember(runId, memberFingerprint);
            }
        }

        if (!failed.isEmpty()) {
            return repository.finishRun(runId, "PARTIALLY_SUCCEEDED", true, 0);
        }

        return repository.finishSuccessfulRun(runId);
    }

    private void reconcileWithInjectedFailure(UUID runId, String memberFingerprint) {
        try {
            repository.reconcileSeenMemberThenFail(runId, memberFingerprint);
            throw new IllegalStateException("M0-13 item fault injection did not fail closed");
        } catch (M013DirectoryProbeRepository.InjectedItemReconciliationFailure expected) {
            repository.markSeenFailed(runId, memberFingerprint, ITEM_FAILURE_CODE);
        }
    }

    private static Set<String> validatedFingerprints(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || !FINGERPRINT_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " contains an invalid fingerprint");
            }
            copy.add(value);
        }
        return Set.copyOf(copy);
    }
}
