package com.yumpoo.platform.identityaccess.application.directory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 正式全量扫描：保留 raw ID 于受控暂存，并安全兼容省略终止游标。 */
public final class FullDirectoryScanCollector {

    private final WeComDirectoryGateway gateway;
    private final int pageSize;

    public FullDirectoryScanCollector(WeComDirectoryGateway gateway, int pageSize) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        if (pageSize < DirectorySnapshotCollector.MIN_PAGE_SIZE
                || pageSize > DirectorySnapshotCollector.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 10000");
        }
        this.pageSize = pageSize;
    }

    public DirectoryScanResult collect(DirectoryScanObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        ScanAttempt first = scanOnce(1, observer);
        if (first.terminalState == WeComDirectoryPage.CursorState.EXPLICIT_END) {
            return first.result(DirectoryScanResult.CursorTerminationMode.EXPLICIT_EMPTY);
        }

        ScanAttempt repeated = scanOnce(2, observer);
        if (!first.members.equals(repeated.members)
                || first.pageCount != repeated.pageCount
                || !first.pageTrajectoryHash.equals(repeated.pageTrajectoryHash)) {
            throw failure(
                    "DIRECTORY_OMITTED_CURSOR_MISMATCH",
                    "Repeated omitted-cursor scans did not produce the same directory snapshot"
            );
        }
        return repeated.result(DirectoryScanResult.CursorTerminationMode.OMITTED_CONFIRMED);
    }

    private ScanAttempt scanOnce(int pass, DirectoryScanObserver observer) {
        TreeSet<String> members = new TreeSet<>();
        Set<String> observedCursors = new HashSet<>();
        List<String> pageHashes = new ArrayList<>();
        String cursor = "";

        for (int pageNumber = 1;
             pageNumber <= DirectorySnapshotCollector.MAX_PAGE_COUNT;
             pageNumber++) {
            WeComDirectoryPage page;
            try {
                page = gateway.fetchPage(cursor, pageSize);
            } catch (WeComDirectoryGatewayException exception) {
                throw failure(
                        "DIRECTORY_SCAN_PROVIDER_FAILED",
                        "The directory provider rejected or could not complete a page request"
                );
            }
            if (page == null) {
                throw failure("DIRECTORY_SCAN_MALFORMED", "The directory provider returned no page");
            }

            List<String> pageMembers = page.memberIds().stream().sorted().toList();
            for (String member : pageMembers) {
                if (!members.add(member)) {
                    throw failure(
                            "DIRECTORY_DUPLICATE_MEMBER",
                            "The directory scan returned a duplicate member identifier"
                    );
                }
            }
            pageHashes.add(DirectoryCanonicalHash.strings("directory-page-v1", pageMembers));
            observer.pageCollected(pass, pageNumber, page.nextCursor(), pageMembers);

            if (page.hasExplicitEnd() || page.hasOmittedCursor()) {
                return new ScanAttempt(
                        List.copyOf(members),
                        pageNumber,
                        page.cursorState(),
                        DirectoryCanonicalHash.strings("directory-pages-v1", pageHashes)
                );
            }
            if (!observedCursors.add(page.nextCursor())) {
                throw failure("DIRECTORY_CURSOR_LOOP", "The directory cursor repeated before termination");
            }
            cursor = page.nextCursor();
        }
        throw failure("DIRECTORY_PAGE_LIMIT", "The directory scan exceeded the page safety limit");
    }

    private static DirectorySyncException failure(String code, String summary) {
        return new DirectorySyncException(code, summary);
    }

    private record ScanAttempt(
            List<String> members,
            int pageCount,
            WeComDirectoryPage.CursorState terminalState,
            String pageTrajectoryHash
    ) {
        private DirectoryScanResult result(DirectoryScanResult.CursorTerminationMode mode) {
            return new DirectoryScanResult(
                    members,
                    mode,
                    pageCount,
                    DirectoryCanonicalHash.strings("directory-members-v1", members),
                    pageTrajectoryHash
            );
        }
    }
}
