package com.yumpoo.platform.foundation.infrastructure.deployment;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class DeploymentDirectoryProbe implements HealthIndicator {

    private final List<Path> roots;

    DeploymentDirectoryProbe(DeploymentPaths paths) {
        this.roots = paths.writableRuntimeRoots();
    }

    @Override
    public Health health() {
        return roots.stream().allMatch(DeploymentDirectoryProbe::canWrite)
                ? Health.up().build()
                : Health.down().build();
    }

    static boolean canWrite(Path root) {
        Path probe = null;
        try {
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                return false;
            }
            probe = Files.createTempFile(root, ".yumpoo-health-", ".probe");
            Files.writeString(
                    probe,
                    "ok",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            if (Files.size(probe) != 2) {
                return false;
            }
            Files.delete(probe);
            probe = null;
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // 无详情健康探针；残留由运维目录清理策略处理。
                }
            }
        }
    }
}
