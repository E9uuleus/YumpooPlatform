package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.MalwareScanVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefenderMpCmdRunScannerTest {

    @Test
    void mapsOnlyDocumentedCleanResultToClean() {
        assertThat(DefenderMpCmdRunScanner.verdictForExitCode(0))
                .isEqualTo(MalwareScanVerdict.CLEAN);
        assertThat(DefenderMpCmdRunScanner.verdictForExitCode(2))
                .isEqualTo(MalwareScanVerdict.INDETERMINATE);
        assertThat(DefenderMpCmdRunScanner.verdictForExitCode(5))
                .isEqualTo(MalwareScanVerdict.UNAVAILABLE);
    }
}
