package com.yumpoo.platform.identityaccess.testing;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberFingerprint;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotFailure;
import com.yumpoo.platform.identityaccess.application.directory.DirectorySnapshotResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M013OmittedCursorConfirmationTest {

    private static final String MEMBER_A = "a".repeat(64);
    private static final String MEMBER_B = "b".repeat(64);
    private static final String CORP = "c".repeat(64);
    private static final String SNAPSHOT = "d".repeat(64);

    @Test
    void confirmsTwoPaginatedSnapshotsAgainstAWideSnapshot() {
        DirectorySnapshotResult.Incomplete first = candidate(2, SNAPSHOT);
        DirectorySnapshotResult.Incomplete repeated = candidate(2, SNAPSHOT);
        DirectorySnapshotResult.Incomplete wide = candidate(1, SNAPSHOT);

        M013OmittedCursorConfirmation.ConfirmedSnapshot confirmed =
                M013OmittedCursorConfirmation.confirm(first, repeated, wide);

        assertThat(confirmed.memberFingerprints()).containsExactlyInAnyOrder(MEMBER_A, MEMBER_B);
        assertThat(confirmed.corpFingerprint()).isEqualTo(CORP);
        assertThat(confirmed.snapshotFingerprint()).isEqualTo(SNAPSHOT);
        assertThat(confirmed.toString())
                .isEqualTo("ConfirmedSnapshot[memberCount=2]")
                .doesNotContain(MEMBER_A, MEMBER_B, CORP, SNAPSHOT);
    }

    @Test
    void rejectsAnOmittedCursorBeforeRealPaginationWasObserved() {
        assertThatThrownBy(() -> M013OmittedCursorConfirmation.confirm(
                candidate(1, SNAPSHOT),
                candidate(2, SNAPSHOT),
                candidate(1, SNAPSHOT)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("M0-13 provider pagination was not observed");
    }

    @Test
    void rejectsAnyFailureOtherThanAnOmittedTerminalCursor() {
        DirectorySnapshotResult.Incomplete transportFailure = new DirectorySnapshotResult.Incomplete(
                fingerprints(),
                CORP,
                SNAPSHOT,
                2,
                DirectorySnapshotFailure.TRANSPORT_ERROR
        );

        assertThatThrownBy(() -> M013OmittedCursorConfirmation.confirm(
                transportFailure,
                candidate(2, SNAPSHOT),
                candidate(1, SNAPSHOT)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not an omitted-cursor candidate");
    }

    @Test
    void rejectsMismatchedWideAndPaginatedSnapshots() {
        assertThatThrownBy(() -> M013OmittedCursorConfirmation.confirm(
                candidate(2, SNAPSHOT),
                candidate(2, SNAPSHOT),
                candidate(1, "e".repeat(64))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("M0-13 narrow and wide snapshots did not match");
    }

    private static DirectorySnapshotResult.Incomplete candidate(
            int completedPageCount,
            String snapshotFingerprint
    ) {
        return new DirectorySnapshotResult.Incomplete(
                fingerprints(),
                CORP,
                snapshotFingerprint,
                completedPageCount,
                DirectorySnapshotFailure.MISSING_CURSOR
        );
    }

    private static List<DirectoryMemberFingerprint> fingerprints() {
        return List.of(
                new DirectoryMemberFingerprint(MEMBER_A),
                new DirectoryMemberFingerprint(MEMBER_B)
        );
    }
}
