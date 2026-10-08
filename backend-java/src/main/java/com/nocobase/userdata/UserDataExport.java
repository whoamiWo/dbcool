package com.nocobase.userdata;

import java.time.Instant;
import java.util.Map;

/**
 * 用户数据导出结果
 */
public class UserDataExport {
    private String userId;
    private String tenantId;
    private Instant exportedAt;
    private String exportedBy;
    private Map<String, Object> userData = new java.util.LinkedHashMap<>();

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getExportedAt() { return exportedAt; }
    public void setExportedAt(Instant exportedAt) { this.exportedAt = exportedAt; }

    public String getExportedBy() { return exportedBy; }
    public void setExportedBy(String exportedBy) { this.exportedBy = exportedBy; }

    public Map<String, Object> getUserData() { return userData; }
    public void setUserData(Map<String, Object> userData) { this.userData = userData; }
}