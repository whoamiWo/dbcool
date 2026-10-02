package com.nocobase.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.storage.minio")
public class MinioStorageProperties {

    private boolean enabled = false;
    private String endpoint;
    private String externalUrl;
    private String accessKey;
    private String secretKey;
    private String bucket = "nocobase";
    private long maxFileSizeMb = 100;
    private List<String> allowedContentTypes = List.of(
            "image/png", "image/jpeg", "image/gif", "application/pdf", "text/plain"
    );

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public long getMaxFileSizeMb() { return maxFileSizeMb; }
    public void setMaxFileSizeMb(long maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }

    public List<String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(List<String> allowedContentTypes) { this.allowedContentTypes = allowedContentTypes; }
}
