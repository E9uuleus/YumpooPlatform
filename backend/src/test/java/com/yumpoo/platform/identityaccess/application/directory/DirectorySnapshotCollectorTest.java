package com.yumpoo.platform.identityaccess.application.directory;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectorySnapshotCollectorTest {

    private static final String CORP_ID = "ww-directory-test";
    private static final String HMAC_KEY = "directory-evidence-key-N4b8K2m6Q9v3X7s1";

    @Test
    void collectsEveryPageAndProducesAStableSortedDeduplicatedSnapshot() {
        QueueGateway gateway = new QueueGateway(
                WeComDirectoryPage.next(
                        List.of("member-b", "member-a", "member-a"),
                        "cursor-1"
                ),
                WeComDirectoryPage.explicitEnd(List.of("member-c", "member-b"))
        );

        DirectorySnapshotResult result = collector(gateway).collect();

        assertThat(result).isInstanceOf(DirectorySnapshotResult.Complete.class);
        DirectorySnapshotResult.Complete complete = (DirectorySnapshotResult.Complete) result;
        assertThat(complete.completedPageCount()).isEqualTo(2);
        assertThat(complete.memberCount()).isEqualTo(3);
        assertThat(complete.memberFingerprints()).isSorted().doesNotHaveDuplicates();
        assertThat(complete.corpFingerprint())
                .isEqualTo("6bbcb7954af01372ac03ac6b4b19eec570c455f50e0329692a6f9c8afd57e5ea");
        assertThat(complete.memberFingerprints())
                .extracting(DirectoryMemberFingerprint::value)
                .containsExactly(
                        "4227d736207268c44c8d930e1ca22fb9a39bfda50382f38fac6350572d467a4d",
                        "58a190d027c70ea7241d4350167acee3b9374ab4736ee690133ead102e660e34",
                        "9fac6e3ecc180e4d0f5fc37853be6322a0221d25dee5497078ee1acdbb93026d"
                );
        assertThat(complete.memberSetFingerprint())
                .isEqualTo("7f02ae321da168578c3265a64224228ae38fb06fba1c11e5ac87fc3c071de5b7");
        assertThat(gateway.requests()).containsExactly(
                new PageRequest("", 1),
                new PageRequest("cursor-1", 1)
        );

        assertThat(complete.toString())
                .isEqualTo("DirectorySnapshotResult.Complete[memberCount=3, completedPageCount=2]")
                .doesNotContain(
                        complete.corpFingerprint(),
                        complete.memberSetFingerprint(),
                        "member-a",
                        "member-b",
                        "member-c"
                );
        assertThat(complete.memberFingerprints()).allSatisfy(fingerprint ->
                assertThat(fingerprint.toString())
                        .isEqualTo("DirectoryMemberFingerprint[REDACTED]")
                        .doesNotContain(fingerprint.value())
        );
    }

    @Test
    void memberSetFingerprintIsIndependentOfPageLayoutAndMemberOrder() {
        DirectorySnapshotResult.Complete split = (DirectorySnapshotResult.Complete) collector(
                new QueueGateway(
                        WeComDirectoryPage.next(List.of("member-c", "member-a"), "next"),
                        WeComDirectoryPage.explicitEnd(List.of("member-b"))
                )
        ).collect();
        DirectorySnapshotResult.Complete single = (DirectorySnapshotResult.Complete) collector(
                new QueueGateway(
                        WeComDirectoryPage.explicitEnd(
                                List.of("member-b", "member-c", "member-a")
                        )
                )
        ).collect();

        assertThat(split.memberFingerprints()).isEqualTo(single.memberFingerprints());
        assertThat(split.memberSetFingerprint()).isEqualTo(single.memberSetFingerprint());
        assertThat(split.corpFingerprint()).isEqualTo(single.corpFingerprint());
    }

    @Test
    void returnsAnIncompleteSnapshotWhenALaterPageFails() {
        WeComDirectoryGateway gateway = new WeComDirectoryGateway() {
            private int callCount;

            @Override
            public WeComDirectoryPage fetchPage(String cursor, int limit) {
                callCount++;
                if (callCount == 1) {
                    return WeComDirectoryPage.next(List.of("member-a"), "next");
                }
                throw new WeComDirectoryGatewayException(DirectorySnapshotFailure.RATE_LIMITED);
            }
        };

        DirectorySnapshotResult result = collector(gateway).collect();

        assertThat(result).isInstanceOf(DirectorySnapshotResult.Incomplete.class);
        DirectorySnapshotResult.Incomplete incomplete = (DirectorySnapshotResult.Incomplete) result;
        assertThat(incomplete.completedPageCount()).isOne();
        assertThat(incomplete.memberCount()).isOne();
        assertThat(incomplete.failure()).isEqualTo(DirectorySnapshotFailure.RATE_LIMITED);
        assertThat(incomplete.toString())
                .isEqualTo(
                        "DirectorySnapshotResult.Incomplete[memberCount=1, "
                                + "completedPageCount=1, failure=RATE_LIMITED]"
                )
                .doesNotContain(
                        incomplete.corpFingerprint(),
                        incomplete.memberSetFingerprint(),
                        "member-a"
                );
    }

    @Test
    void repeatedCursorFailsClosedWithoutRequestingItAgain() {
        QueueGateway gateway = new QueueGateway(
                WeComDirectoryPage.next(List.of("member-a"), "same-cursor"),
                WeComDirectoryPage.next(List.of("member-b"), "same-cursor")
        );

        DirectorySnapshotResult.Incomplete result =
                (DirectorySnapshotResult.Incomplete) collector(gateway).collect();

        assertThat(result.failure()).isEqualTo(DirectorySnapshotFailure.CURSOR_LOOP);
        assertThat(result.completedPageCount()).isEqualTo(2);
        assertThat(gateway.requests()).hasSize(2);
    }

    @Test
    void pageLimitFailsClosedBeforeRequestingPageTenThousandAndOne() {
        AtomicInteger requests = new AtomicInteger();
        WeComDirectoryGateway gateway = (cursor, limit) -> {
            int page = requests.incrementAndGet();
            return WeComDirectoryPage.next(List.of(), "cursor-" + page);
        };

        DirectorySnapshotResult.Incomplete result =
                (DirectorySnapshotResult.Incomplete) collector(gateway).collect();

        assertThat(result.failure()).isEqualTo(DirectorySnapshotFailure.PAGE_LIMIT_EXCEEDED);
        assertThat(result.completedPageCount()).isEqualTo(DirectorySnapshotCollector.MAX_PAGE_COUNT);
        assertThat(requests).hasValue(DirectorySnapshotCollector.MAX_PAGE_COUNT);
    }

    @Test
    void nullPageIsClassifiedAsAnIncompleteMalformedResponse() {
        DirectorySnapshotResult.Incomplete result = (DirectorySnapshotResult.Incomplete)
                collector((cursor, limit) -> null).collect();

        assertThat(result.failure()).isEqualTo(DirectorySnapshotFailure.MALFORMED_RESPONSE);
        assertThat(result.completedPageCount()).isZero();
        assertThat(result.memberCount()).isZero();
    }

    @Test
    void pageModelsNextExplicitEndAndOmittedCursorWithoutLeakingValues() {
        WeComDirectoryPage next = WeComDirectoryPage.next(List.of("member-a"), "cursor-a");
        WeComDirectoryPage explicitEnd = WeComDirectoryPage.explicitEnd(List.of("member-a"));
        WeComDirectoryPage omitted = WeComDirectoryPage.omitted(List.of("member-a"));

        assertThat(next.hasNextPage()).isTrue();
        assertThat(explicitEnd.hasExplicitEnd()).isTrue();
        assertThat(omitted.hasOmittedCursor()).isTrue();
        assertThat(next.toString())
                .isEqualTo("WeComDirectoryPage[memberCount=1, cursorState=NEXT]")
                .doesNotContain("member-a", "cursor-a");
        assertThatThrownBy(() -> new WeComDirectoryPage(
                List.of(),
                null,
                WeComDirectoryPage.CursorState.NEXT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("next cursor has an invalid format");
        assertThatThrownBy(() -> WeComDirectoryPage.next(List.of(), " \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("next cursor has an invalid format");
        assertThatThrownBy(() -> new WeComDirectoryPage(
                List.of(),
                "cursor-a",
                WeComDirectoryPage.CursorState.EXPLICIT_END
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeComDirectoryPage(
                List.of(),
                "",
                WeComDirectoryPage.CursorState.OMITTED
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void omittedTerminalCursorKeepsTheLastPageButFailsClosed() {
        QueueGateway gateway = new QueueGateway(
                WeComDirectoryPage.next(List.of("member-a"), "next"),
                WeComDirectoryPage.omitted(List.of("member-b"))
        );

        DirectorySnapshotResult.Incomplete result =
                (DirectorySnapshotResult.Incomplete) collector(gateway).collect();

        assertThat(result.failure()).isEqualTo(DirectorySnapshotFailure.MISSING_CURSOR);
        assertThat(result.completedPageCount()).isEqualTo(2);
        assertThat(result.memberCount()).isEqualTo(2);
        assertThat(result.memberFingerprints()).isSorted().doesNotHaveDuplicates();
        assertThat(gateway.requests()).containsExactly(
                new PageRequest("", 1),
                new PageRequest("next", 1)
        );
    }

    @Test
    void malformedProviderCursorFailureCannotCompleteTheSnapshot() {
        AtomicInteger requests = new AtomicInteger();
        WeComDirectoryGateway gateway = (cursor, limit) -> {
            if (requests.getAndIncrement() == 0) {
                return WeComDirectoryPage.next(List.of("member-a"), "next");
            }
            throw new WeComDirectoryGatewayException(
                    DirectorySnapshotFailure.MALFORMED_RESPONSE
            );
        };

        DirectorySnapshotResult.Incomplete result =
                (DirectorySnapshotResult.Incomplete) collector(gateway).collect();

        assertThat(result.failure()).isEqualTo(DirectorySnapshotFailure.MALFORMED_RESPONSE);
        assertThat(result.completedPageCount()).isOne();
        assertThat(result.memberCount()).isOne();
        assertThat(requests).hasValue(2);
    }

    @Test
    void rejectsUnsafeConfiguration() {
        WeComDirectoryGateway gateway =
                (cursor, limit) -> WeComDirectoryPage.explicitEnd(List.of());

        assertThatThrownBy(() -> new DirectorySnapshotCollector(gateway, CORP_ID, "too-short", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("directory evidence HMAC key does not meet the strength policy");
        assertThatThrownBy(() -> new DirectorySnapshotCollector(
                gateway,
                CORP_ID,
                "x".repeat(32),
                1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DirectorySnapshotCollector(gateway, CORP_ID, HMAC_KEY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pageSize must be between 1 and 10000");
        assertThatThrownBy(() -> new DirectorySnapshotCollector(gateway, CORP_ID, HMAC_KEY, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DirectorySnapshotCollector collector(WeComDirectoryGateway gateway) {
        return new DirectorySnapshotCollector(gateway, CORP_ID, HMAC_KEY, 1);
    }

    private static final class QueueGateway implements WeComDirectoryGateway {

        private final Deque<WeComDirectoryPage> pages;
        private final List<PageRequest> requests = new ArrayList<>();

        private QueueGateway(WeComDirectoryPage... pages) {
            this.pages = new ArrayDeque<>(List.of(pages));
        }

        @Override
        public WeComDirectoryPage fetchPage(String cursor, int limit) {
            requests.add(new PageRequest(cursor, limit));
            if (pages.isEmpty()) {
                throw new AssertionError("unexpected directory page request");
            }
            return pages.removeFirst();
        }

        private List<PageRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private record PageRequest(String cursor, int limit) {
    }
}
