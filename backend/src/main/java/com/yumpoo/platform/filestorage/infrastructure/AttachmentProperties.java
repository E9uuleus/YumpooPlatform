package com.yumpoo.platform.filestorage.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "yumpoo.attachments")
public class AttachmentProperties {
    private long companyQuotaBytes = 100L * 1024 * 1024 * 1024;
    private long projectQuotaBytes = 10L * 1024 * 1024 * 1024;
    private int scanConcurrency = 2;
    private Duration scanLease = Duration.ofMinutes(5);
    private Duration uploadLease = Duration.ofMinutes(15);
    private Duration firstScanRetry = Duration.ofSeconds(5);
    private Duration secondScanRetry = Duration.ofSeconds(30);
    private String attachmentRoot = "out/attachments";
    private String uploadTempRoot = "out/upload-temp";
    private String defenderExecutable = "";
    private Duration defenderTimeout = Duration.ofMinutes(2);
    private boolean cleanupDeleteEnabled;
    private String cleanupApprovalReference = "";
    private Duration maintenanceInitialDelay = Duration.ofMinutes(5);
    private Duration maintenancePollDelay = Duration.ofMinutes(1);
    private Duration maintenanceInterval = Duration.ofHours(24);
    private int maintenanceBatchSize = 100;

    public long getCompanyQuotaBytes() { return companyQuotaBytes; }
    public void setCompanyQuotaBytes(long value) { companyQuotaBytes = value; }
    public long getProjectQuotaBytes() { return projectQuotaBytes; }
    public void setProjectQuotaBytes(long value) { projectQuotaBytes = value; }
    public int getScanConcurrency() { return scanConcurrency; }
    public void setScanConcurrency(int value) { scanConcurrency = value; }
    public Duration getScanLease() { return scanLease; }
    public void setScanLease(Duration value) { scanLease = value; }
    public Duration getUploadLease() { return uploadLease; }
    public void setUploadLease(Duration value) { uploadLease = value; }
    public Duration getFirstScanRetry() { return firstScanRetry; }
    public void setFirstScanRetry(Duration value) { firstScanRetry = value; }
    public Duration getSecondScanRetry() { return secondScanRetry; }
    public void setSecondScanRetry(Duration value) { secondScanRetry = value; }
    public String getAttachmentRoot() { return attachmentRoot; }
    public void setAttachmentRoot(String value) { attachmentRoot = value; }
    public String getUploadTempRoot() { return uploadTempRoot; }
    public void setUploadTempRoot(String value) { uploadTempRoot = value; }
    public String getDefenderExecutable() { return defenderExecutable; }
    public void setDefenderExecutable(String value) { defenderExecutable = value; }
    public Duration getDefenderTimeout() { return defenderTimeout; }
    public void setDefenderTimeout(Duration value) { defenderTimeout = value; }
    public boolean isCleanupDeleteEnabled() { return cleanupDeleteEnabled; }
    public void setCleanupDeleteEnabled(boolean value) { cleanupDeleteEnabled = value; }
    public String getCleanupApprovalReference() { return cleanupApprovalReference; }
    public void setCleanupApprovalReference(String value) { cleanupApprovalReference = value; }
    public Duration getMaintenanceInitialDelay() { return maintenanceInitialDelay; }
    public void setMaintenanceInitialDelay(Duration value) { maintenanceInitialDelay = value; }
    public Duration getMaintenancePollDelay() { return maintenancePollDelay; }
    public void setMaintenancePollDelay(Duration value) { maintenancePollDelay = value; }
    public Duration getMaintenanceInterval() { return maintenanceInterval; }
    public void setMaintenanceInterval(Duration value) { maintenanceInterval = value; }
    public int getMaintenanceBatchSize() { return maintenanceBatchSize; }
    public void setMaintenanceBatchSize(int value) { maintenanceBatchSize = value; }
}
