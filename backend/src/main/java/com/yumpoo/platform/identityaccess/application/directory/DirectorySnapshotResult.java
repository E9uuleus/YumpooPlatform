package com.yumpoo.platform.identityaccess.application.directory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 完整页集或安全失败前已完成页集的脱敏结果。 */
public sealed interface DirectorySnapshotResult
        permits DirectorySnapshotResult.Complete, DirectorySnapshotResult.Incomplete {

    List<DirectoryMemberFingerprint> memberFingerprints();

    String corpFingerprint();

    String memberSetFingerprint();

    int completedPageCount();

    default int memberCount() {
        return memberFingerprints().size();
    }

    record Complete(
            List<DirectoryMemberFingerprint> memberFingerprints,
            String corpFingerprint,
            String memberSetFingerprint,
            int completedPageCount
    ) implements DirectorySnapshotResult {

        public Complete {
            memberFingerprints = validatedFingerprints(memberFingerprints);
            corpFingerprint = validatedHmac(corpFingerprint, "corpFingerprint");
            memberSetFingerprint = validatedHmac(memberSetFingerprint, "memberSetFingerprint");
            if (completedPageCount < 1) {
                throw new IllegalArgumentException("a complete snapshot must contain at least one page");
            }
        }

        @Override
        public String toString() {
            return "DirectorySnapshotResult.Complete[memberCount=" + memberFingerprints.size()
                    + ", completedPageCount=" + completedPageCount + "]";
        }
    }

    record Incomplete(
            List<DirectoryMemberFingerprint> memberFingerprints,
            String corpFingerprint,
            String memberSetFingerprint,
            int completedPageCount,
            DirectorySnapshotFailure failure
    ) implements DirectorySnapshotResult {

        public Incomplete {
            memberFingerprints = validatedFingerprints(memberFingerprints);
            corpFingerprint = validatedHmac(corpFingerprint, "corpFingerprint");
            memberSetFingerprint = validatedHmac(memberSetFingerprint, "memberSetFingerprint");
            if (completedPageCount < 0) {
                throw new IllegalArgumentException("completedPageCount must not be negative");
            }
            Objects.requireNonNull(failure, "failure must not be null");
        }

        @Override
        public String toString() {
            return "DirectorySnapshotResult.Incomplete[memberCount=" + memberFingerprints.size()
                    + ", completedPageCount=" + completedPageCount
                    + ", failure=" + failure + "]";
        }
    }

    private static List<DirectoryMemberFingerprint> validatedFingerprints(
            List<DirectoryMemberFingerprint> fingerprints
    ) {
        Objects.requireNonNull(fingerprints, "memberFingerprints must not be null");
        List<DirectoryMemberFingerprint> copy = List.copyOf(fingerprints);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("memberFingerprints must not contain null");
        }
        Set<DirectoryMemberFingerprint> unique = new HashSet<>(copy);
        if (unique.size() != copy.size()) {
            throw new IllegalArgumentException("memberFingerprints must be unique");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException("memberFingerprints must be strictly sorted");
            }
        }
        return copy;
    }

    private static String validatedHmac(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!HmacPattern.VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase HMAC-SHA-256 hex");
        }
        return value;
    }

    final class HmacPattern {
        private static final Pattern VALUE = Pattern.compile("^[0-9a-f]{64}$");

        private HmacPattern() {
        }
    }
}
