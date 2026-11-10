package com.nocobase.integration.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 钉钉组织架构同步兼容入口，仅支持手工调用。
 * <p>每次同步通过 {@link DingTalkOrgSyncService} 拉取钉钉通讯录数据并更新本地库。
 */
@Component
public class DingTalkSyncJob {

    private static final Logger log = LoggerFactory.getLogger(DingTalkSyncJob.class);

    private final DingTalkOrgSyncService syncService;

    public DingTalkSyncJob(DingTalkOrgSyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * 保留给存量代码手工触发；调度只由 {@link DingTalkOrgSyncService#scheduledSync()} 注册。
     */
    public void syncOrganization() {
        log.info("[DingTalk] 手工触发组织架构同步...");
        try {
            syncService.syncOrganization("tenant_default");
            log.info("[DingTalk] 组织架构同步完成");
        } catch (Exception e) {
            log.error("[DingTalk] 组织架构同步失败: {}", e.getMessage(), e);
        }
    }
}