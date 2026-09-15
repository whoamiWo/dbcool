package com.nocobase.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时确保默认租户存在(Week 41 D6 修复 — 生产阻断项)。
 *
 * <p>背景:{@code TenantService.seedDefaultIfEmpty()} 此前没有任何生产调用方
 * (既无 CommandLineRunner,也无 ApplicationReadyEvent / @PostConstruct),
 * 导致默认租户永远不会被初始化,新建租户与租户查询流程无法闭环。
 *
 * <p>V14__tenant.sql 已 seed 了 {@code tenant_default},此处作为兜底
 * (覆盖空库但未跑迁移、或数据被清理的环境)。方法本身幂等
 * ({@code count() > 0} 直接返回),重复执行无副作用。
 *
 * <p>容错:seed 失败仅记 ERROR 日志,不阻断应用启动 —— 只读库或权限不足时
 * 不应导致整个服务起不来,但必须留下可排查的日志。
 */
@Component
public class TenantSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSeedRunner.class);

    private final TenantService tenantService;

    public TenantSeedRunner(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public void run(String... args) {
        try {
            tenantService.seedDefaultIfEmpty();
        } catch (Exception e) {
            log.error("[tenant] 默认租户初始化失败,多租户功能可能异常", e);
        }
    }
}
