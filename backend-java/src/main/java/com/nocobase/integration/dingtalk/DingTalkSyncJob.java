package com.nocobase.integration.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 钉钉组织架构同步定时任务 — 每小时拉取部门/用户列表。
 *
 * <p><b>Week 2 任务</b>: 实现 @Scheduled 每小时自动同步。
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
     * 每小时执行一次组织架构同步。
     * <p>初始延迟 30 秒，之后每小时执行。
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 30_000)
    public void syncOrganization() {
        log.info("[DingTalk] 开始定时同步组织架构...");
        try {
            syncService.syncOrganization("tenant_default");
            log.info("[DingTalk] 组织架构同步完成");
        } catch (Exception e) {
            log.error("[DingTalk] 组织架构同步失败: {}", e.getMessage(), e);
        }
    }
}