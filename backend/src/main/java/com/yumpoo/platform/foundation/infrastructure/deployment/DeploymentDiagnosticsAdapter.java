package com.yumpoo.platform.foundation.infrastructure.deployment;

import com.yumpoo.platform.foundation.application.diagnostics.DeploymentDiagnosticsPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DeploymentDiagnosticsAdapter implements DeploymentDiagnosticsPort {

    private final Environment environment;

    public DeploymentDiagnosticsAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Snapshot read() {
        record Root(String key, String fallback, String purpose, boolean writable) {}
        List<Root> roots = List.of(
            new Root("yumpoo.deployment.release-root", ".", "APPLICATION", false),
            new Root("yumpoo.attachments.attachment-root", "out/attachments", "ATTACHMENTS", true),
            new Root("yumpoo.attachments.upload-temp-root", "out/upload-temp", "UPLOAD_TEMP", true),
            new Root(
                "yumpoo.deployment.log-root",
                Path.of(environment.getProperty("logging.file.name", "out/logs/yumpoo-server.log"))
                    .toAbsolutePath()
                    .getParent()
                    .toString(),
                "LOGS",
                true
            )
        );
        Map<FileStore, List<String>> purposes = new LinkedHashMap<>();
        Map<FileStore, Path> locations = new HashMap<>();
        boolean healthy = true;
        for (Root root : roots) {
            try {
                Path path = Path.of(environment.getProperty(root.key, root.fallback)).toAbsolutePath().normalize();
                if (root.writable && !DeploymentDirectoryProbe.canWrite(path)) healthy = false;
                while (path != null && !Files.exists(path)) path = path.getParent();
                if (path == null) continue;
                FileStore store = Files.getFileStore(path);
                purposes.computeIfAbsent(store, ignored -> new ArrayList<>()).add(root.purpose);
                locations.put(store, path);
            } catch (Exception ignored) {
                healthy = false;
            }
        }
        List<Volume> volumes = new ArrayList<>();
        for (var entry : purposes.entrySet()) {
            try {
                String root = locations.get(entry.getKey()).getRoot().toString();
                String id = UUID.nameUUIDFromBytes(
                    (entry.getKey().name() + root).getBytes(StandardCharsets.UTF_8)
                ).toString();
                String label = root.matches("[A-Za-z]:[\\\\/]?")
                    ? root.substring(0, 2)
                    : "磁盘 " + (volumes.size() + 1);
                volumes.add(
                    new Volume(
                        id,
                        label,
                        entry.getKey().getTotalSpace(),
                        entry.getKey().getUsableSpace(),
                        entry.getValue()
                    )
                );
            } catch (Exception ignored) {
                healthy = false;
            }
        }
        return new Snapshot(healthy ? "UP" : "DOWN", List.copyOf(volumes));
    }
}
