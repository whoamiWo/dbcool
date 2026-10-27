package com.nocobase.integration.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 外部数据源定时同步作业（B3 返工）。
 *
 * <p>修复前 {@code integration/datasource/} 包内无任何 {@code @Scheduled}，
 * {@code lastSyncedAt} 仅在创建虚拟 Collection 时赋值一次（一次性快照），
 * 声明的"定时全量/增量同步"并不存在。
 *
 * <p>本作业每小时整点扫描所有「启用定时同步」的数据源，按其
 * {@code syncMode}(FULL / INCREMENTAL) 触发一次同步，并推进同步水位。
 *
 * <p><b>同步语义</b>：本作业负责**刷新结构快照与水位**，
 * 具体刷新动作由 {@link ExternalDataSourceService#syncDatasource} 执行；
 * 单个数据源失败不影响其他数据源（逐个 try-catch，继续下一个）。
 */
@Component
public class ExternalDataSourceSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataSourceSyncJob.class);

    private final ExternalDataSourceService service;
    private final ExternalDataSourceRepository repository;

    public ExternalDataSourceSyncJob(ExternalDataSourceService service,
                                     ExternalDataSourceRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** 每小时整点执行一次。 */
    @Scheduled(cron = "${external-datasource.sync.cron:0 0 * * * ?}")
    public void syncAll() {
        List<ExternalDataSourceEntity> due;
        try {
            due = repository.findBySyncEnabledTrue();
        } catch (Exception e) {
            // V72 迁移未执行时列不存在，降级为不同步，避免启动/调度崩溃
            log.warn("[ext-ds-sync] 查询启用同步的数据源失败（可能 V72 未迁移），跳过本轮: {}", e.getMessage());
            return;
        }

        if (due == null || due.isEmpty()) {
            log.debug("[ext-ds-sync] 无启用定时同步的数据源");
            return;
        }

        int ok = 0;
        int failed = 0;
        for (ExternalDataSourceEntity ds : due) {
            try {
                service.syncDatasource(ds.getId(), ds.getTenantId());
                ok++;
            } catch (Exception e) {
                failed++;
                log.error("[ext-ds-sync] 同步失败 id={} name={} err={}",
                        ds.getId(), ds.getName(), e.getMessage());
            }
        }
        log.info("[ext-ds-sync] 本轮同步完成: 成功={} 失败={} 总数={} at={}", ok, failed, due.size(), Instant.now());
    }
}
