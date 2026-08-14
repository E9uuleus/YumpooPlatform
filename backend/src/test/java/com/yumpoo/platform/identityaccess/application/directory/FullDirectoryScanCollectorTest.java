package com.yumpoo.platform.identityaccess.application.directory;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FullDirectoryScanCollectorTest {

    @Test
    void acceptsExplicitEmptyCursorAfterOneScan() {
        RecordingGateway gateway = new RecordingGateway(
                WeComDirectoryPage.next(List.of("member-b"), "cursor-1"),
                WeComDirectoryPage.explicitEnd(List.of("member-a"))
        );

        DirectoryScanResult result = new FullDirectoryScanCollector(gateway, 1000)
                .collect((pass, page, cursor, members) -> { });

        assertThat(result.externalUserIds()).containsExactly("member-a", "member-b");
        assertThat(result.terminationMode())
                .isEqualTo(DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY);
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(gateway.requestedCursors).containsExactly("", "cursor-1");
    }

    @Test
    void confirmsOmittedCursorWithIdenticalSecondScan() {
        RecordingGateway gateway = new RecordingGateway(
                WeComDirectoryPage.omitted(List.of("member-b", "member-a")),
                WeComDirectoryPage.omitted(List.of("member-a", "member-b"))
        );
        List<Integer> observedPasses = new ArrayList<>();

        DirectoryScanResult result = new FullDirectoryScanCollector(gateway, 1000)
                .collect((pass, page, cursor, members) -> observedPasses.add(pass));

        assertThat(result.terminationMode())
                .isEqualTo(DirectoryScanResult.CursorTerminationMode.OMITTED_CONFIRMED);
        assertThat(observedPasses).containsExactly(1, 2);
    }

    @Test
    void rejectsMismatchedRepeatedScanAndDuplicateMembers() {
        RecordingGateway mismatch = new RecordingGateway(
                WeComDirectoryPage.omitted(List.of("member-a")),
                WeComDirectoryPage.omitted(List.of("member-b"))
        );
        assertThatThrownBy(() -> new FullDirectoryScanCollector(mismatch, 1000)
                .collect((pass, page, cursor, members) -> { }))
                .isInstanceOfSatisfying(DirectorySyncException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo("DIRECTORY_OMITTED_CURSOR_MISMATCH"));

        RecordingGateway duplicate = new RecordingGateway(
                WeComDirectoryPage.next(List.of("member-a"), "cursor-1"),
                WeComDirectoryPage.explicitEnd(List.of("member-a"))
        );
        assertThatThrownBy(() -> new FullDirectoryScanCollector(duplicate, 1000)
                .collect((pass, page, cursor, members) -> { }))
                .isInstanceOfSatisfying(DirectorySyncException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo("DIRECTORY_DUPLICATE_MEMBER"));
    }

    @Test
    void rejectsCursorLoop() {
        RecordingGateway gateway = new RecordingGateway(
                WeComDirectoryPage.next(List.of("member-a"), "cursor-1"),
                WeComDirectoryPage.next(List.of("member-b"), "cursor-1")
        );

        assertThatThrownBy(() -> new FullDirectoryScanCollector(gateway, 1000)
                .collect((pass, page, cursor, members) -> { }))
                .isInstanceOfSatisfying(DirectorySyncException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("DIRECTORY_CURSOR_LOOP"));
    }

    private static final class RecordingGateway implements WeComDirectoryGateway {
        private final ArrayDeque<WeComDirectoryPage> pages;
        private final List<String> requestedCursors = new ArrayList<>();

        private RecordingGateway(WeComDirectoryPage... pages) {
            this.pages = new ArrayDeque<>(List.of(pages));
        }

        @Override
        public WeComDirectoryPage fetchPage(String cursor, int limit) {
            requestedCursors.add(cursor);
            return pages.removeFirst();
        }
    }
}
