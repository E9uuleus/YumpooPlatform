package com.yumpoo.platform.identityaccess.testing;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberFingerprint;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotFailure;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotResult;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** M0-13 live-only compatibility for WeCom terminal pages that omit next_cursor. */
public final class M013OmittedCursorConfirmation {

    private M013OmittedCursorConfirmation() {
    }

    public static ConfirmedSnapshot confirm(
            DirectorySnapshotResult firstPaginated,
            DirectorySnapshotResult repeatedPaginated,
            DirectorySnapshotResult wide
    ) {
        DirectorySnapshotResult.Incomplete first = requireCandidate(
                firstPaginated,
                true,
                "first paginated snapshot"
        );
        DirectorySnapshotResult.Incomplete repeated = requireCandidate(
                repeatedPaginated,
                true,
                "repeated paginated snapshot"
        );
        DirectorySnapshotResult.Incomplete wideCandidate = requireCandidate(
                wide,
                false,
                "wide snapshot"
        );

        Set<String> memberFingerprints = fingerprintValues(first);
        if (!first.corpFingerprint().equals(repeated.corpFingerprint())
                || !first.corpFingerprint().equals(wideCandidate.corpFingerprint())
                || !first.memberSetFingerprint().equals(repeated.memberSetFingerprint())
                || !first.memberSetFingerprint().equals(wideCandidate.memberSetFingerprint())
                || !memberFingerprints.equals(fingerprintValues(repeated))
                || !memberFingerprints.equals(fingerprintValues(wideCandidate))) {
            throw new IllegalStateException("M0-13 narrow and wide snapshots did not match");
        }

        return new ConfirmedSnapshot(
                memberFingerprints,
                first.corpFingerprint(),
                first.memberSetFingerprint()
        );
    }

    private static DirectorySnapshotResult.Incomplete requireCandidate(
            DirectorySnapshotResult result,
            boolean paginationRequired,
            String description
    ) {
        Objects.requireNonNull(result, "snapshot must not be null");
        if (!(result instanceof DirectorySnapshotResult.Incomplete incomplete)
                || incomplete.failure() != DirectorySnapshotFailure.MISSING_CURSOR) {
            throw new IllegalStateException("M0-13 " + description + " is not an omitted-cursor candidate");
        }
        if (incomplete.memberCount() == 0) {
            throw new IllegalStateException("M0-13 " + description + " is empty");
        }
        if (paginationRequired && incomplete.completedPageCount() <= 1) {
            throw new IllegalStateException("M0-13 provider pagination was not observed");
        }
        return incomplete;
    }

    private static Set<String> fingerprintValues(DirectorySnapshotResult snapshot) {
        Set<String> values = new LinkedHashSet<>();
        for (DirectoryMemberFingerprint fingerprint : snapshot.memberFingerprints()) {
            values.add(fingerprint.value());
        }
        if (values.size() != snapshot.memberCount()) {
            throw new IllegalStateException("M0-13 snapshot contains duplicate fingerprints");
        }
        return Set.copyOf(values);
    }

    public record ConfirmedSnapshot(
            Set<String> memberFingerprints,
            String corpFingerprint,
            String snapshotFingerprint
    ) {

        public ConfirmedSnapshot {
            memberFingerprints = Set.copyOf(memberFingerprints);
            Objects.requireNonNull(corpFingerprint, "corpFingerprint must not be null");
            Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint must not be null");
        }

        @Override
        public String toString() {
            return "ConfirmedSnapshot[memberCount=" + memberFingerprints.size() + "]";
        }
    }
}
