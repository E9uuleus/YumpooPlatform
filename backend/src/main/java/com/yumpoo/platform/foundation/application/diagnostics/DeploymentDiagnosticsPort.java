package com.yumpoo.platform.foundation.application.diagnostics;

import java.util.List;

public interface DeploymentDiagnosticsPort {
    Snapshot read();

    record Volume(String id, String label, long totalBytes, long freeBytes, List<String> purposes) {}

    record Snapshot(String directoryHealth, List<Volume> volumes) {}
}
