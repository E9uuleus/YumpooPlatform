package com.yumpoo.platform.foundation.infrastructure.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Windows 单机部署所需的外部路径和公开地址。 */
@ConfigurationProperties(prefix = "yumpoo.deployment")
public class DeploymentProperties {

    private String publicBaseUrl;
    private String releaseRoot;
    private String configRoot;
    private String secretsRoot;
    private String attachmentRoot;
    private String uploadTempRoot;
    private String logRoot;

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getReleaseRoot() {
        return releaseRoot;
    }

    public void setReleaseRoot(String releaseRoot) {
        this.releaseRoot = releaseRoot;
    }

    public String getConfigRoot() {
        return configRoot;
    }

    public void setConfigRoot(String configRoot) {
        this.configRoot = configRoot;
    }

    public String getSecretsRoot() {
        return secretsRoot;
    }

    public void setSecretsRoot(String secretsRoot) {
        this.secretsRoot = secretsRoot;
    }

    public String getAttachmentRoot() {
        return attachmentRoot;
    }

    public void setAttachmentRoot(String attachmentRoot) {
        this.attachmentRoot = attachmentRoot;
    }

    public String getUploadTempRoot() {
        return uploadTempRoot;
    }

    public void setUploadTempRoot(String uploadTempRoot) {
        this.uploadTempRoot = uploadTempRoot;
    }

    public String getLogRoot() {
        return logRoot;
    }

    public void setLogRoot(String logRoot) {
        this.logRoot = logRoot;
    }
}
