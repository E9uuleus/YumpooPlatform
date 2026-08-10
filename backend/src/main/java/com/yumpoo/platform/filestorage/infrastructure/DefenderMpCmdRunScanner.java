package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.MalwareScanVerdict;
import com.yumpoo.platform.filestorage.application.MalwareScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Defender MpCmdRun 适配器。官方退出码 2 同时代表威胁和扫描错误，
 * 因而保守映射为 INDETERMINATE，绝不根据本地化控制台文本猜测结果。
 */
public final class DefenderMpCmdRunScanner implements MalwareScanner {

    private static final int OUTPUT_DRAIN_BUFFER_BYTES = 8192;

    private final Path executable;
    private final Duration timeout;

    public DefenderMpCmdRunScanner(Path executable, Duration timeout) {
        this.executable = Objects.requireNonNull(executable, "executable must not be null")
                .toAbsolutePath()
                .normalize();
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public MalwareScanVerdict scan(Path sealedContent) {
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || sealedContent == null
                || !Files.isRegularFile(sealedContent, LinkOption.NOFOLLOW_LINKS)) {
            return MalwareScanVerdict.UNAVAILABLE;
        }
        List<String> command = List.of(
                executable.toString(),
                "-Scan",
                "-ScanType", "3",
                "-File", sealedContent.toAbsolutePath().normalize().toString(),
                "-DisableRemediation"
        );
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            return MalwareScanVerdict.UNAVAILABLE;
        }

        Thread outputDrainer = Thread.ofVirtual().start(() -> drain(process.getInputStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outputDrainer.join(Duration.ofSeconds(2));
                return MalwareScanVerdict.UNAVAILABLE;
            }
            outputDrainer.join(Duration.ofSeconds(2));
            return verdictForExitCode(process.exitValue());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return MalwareScanVerdict.UNAVAILABLE;
        }
    }

    static MalwareScanVerdict verdictForExitCode(int exitCode) {
        if (exitCode == 0) {
            return MalwareScanVerdict.CLEAN;
        }
        if (exitCode == 2) {
            return MalwareScanVerdict.INDETERMINATE;
        }
        return MalwareScanVerdict.UNAVAILABLE;
    }

    private static void drain(InputStream input) {
        try (input) {
            byte[] buffer = new byte[OUTPUT_DRAIN_BUFFER_BYTES];
            while (input.read(buffer) != -1) {
                // 只为防止子进程管道阻塞；输出不解析、不保存、不记录。
            }
        } catch (IOException ignored) {
            // 进程退出或强制结束时管道关闭，调用方仍按退出/超时结果失败关闭。
        }
    }
}
