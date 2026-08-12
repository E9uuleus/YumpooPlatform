package com.yumpoo.platform.foundation.infrastructure.deployment;

import java.nio.file.Path;
import java.util.List;

record DeploymentPaths(
        Path releaseRoot,
        Path configRoot,
        Path secretsRoot,
        Path attachmentRoot,
        Path uploadTempRoot,
        Path logRoot
) {
    List<Path> writableRuntimeRoots() {
        return List.of(attachmentRoot, uploadTempRoot, logRoot);
    }
}
