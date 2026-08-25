package com.yumpoo.platform.filestorage.application;

import java.time.Duration;

public record AttachmentRuntimeSettings(long companyQuotaBytes, long projectQuotaBytes,
        Duration scanLease, Duration uploadLease, Duration firstScanRetry,
        Duration secondScanRetry) {}
