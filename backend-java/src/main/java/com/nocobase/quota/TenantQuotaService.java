package com.nocobase.quota;

import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import com.nocobase.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 租户配额与计量服务（PHASE92 TN-2 核心实现）
 *
 * <p>三类配额：API 调用 / 存储 / 席位
 * 所有配额均为 fail-close：超限直接返回 429/403
 */
@Component
public class TenantQuotaService {
    private static final Logger log = LoggerFactory.getLogger(TenantQuotaService.class);

    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public TenantQuotaService(TenantRepository tenantRepository, AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    // ==================== API 配额 ====================

    /**
     * 检查并消耗 API 调用配额
     * @param tenantId 租户 ID
     * @return true: 有配额并成功消耗；false: 超限
     * @throws ResponseStatusException 429 超限时抛出
     */
    // @Modifying 的 UPDATE **必须在事务中执行**，否则 Spring Data 抛
    // InvalidDataAccessApiUsageException（实测踩到：未加事务时 UPDATE 从未执行，
    // 又因下面的 catch 放行而表现为"配额完全不生效"）。
    @org.springframework.transaction.annotation.Transactional
    public boolean checkAndConsumeApiQuota(String tenantId) {
        // 原子 UPDATE：affected=1 消耗成功，0 表示已达上限
        int affected;
        try {
            affected = tenantRepository.consumeApiQuota(tenantId);
        } catch (Exception e) {
            // 配额服务异常不得阻塞业务（与 AuditService 同样的容错取向）。
            // 用 ERROR 级别：这类异常意味着配额整体失效，必须能在日志里被看见。
            log.error("[quota] 配额消耗异常，放行 tenant={} err={}", tenantId, e.toString());
            return true;
        }

        if (affected == 0) {
            // 注意：可能是"已达上限"，也可能是租户不存在 —— 单独确认一次避免误判
            TenantEntity t = tenantRepository.findById(tenantId).orElse(null);
            if (t == null) {
                throw new IllegalArgumentException("Tenant not found: " + tenantId);
            }
            log.warn("[quota] API 调用超限 tenant={} usage={} limit={}",
                    tenantId, t.getApiCallUsage(), t.getApiCallLimit());
            auditService.log(tenantId, null, "system", "QUOTA_EXCEEDED", "API_CALL", tenantId,
                String.format("API 调用次数达到上限 %d/%d",
                        t.getApiCallUsage(), t.getApiCallLimit()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "API 调用次数已达上限，请稍后重试");
        }
        return true;
    }

    /**
     * 每分钟重置所有租户的 API 用量 —— 配额是**按分钟**计的，必须滚动清零。
     *
     * <p>此前这个方法**没有任何定时调用**，导致配额用满后租户被永久封禁
     * （实测：压测后 usage 停在 3600，之后所有请求都 429）。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @org.springframework.transaction.annotation.Transactional
    public void resetAllApiUsagePeriodically() {
        int n = tenantRepository.resetAllApiUsage();
        log.debug("[quota] 每分钟重置 API 用量，影响 {} 个租户", n);
    }

    /**
     * 重置单个租户的 API 调用计数（手动/管理用）
     */
    public void resetApiUsage(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null) {
            tenant.setApiCallUsage(0L);
            tenantRepository.save(tenant);
        }
    }

    // ==================== 存储配额 ====================

    /**
     * 检查并消耗存储配额
     * @param tenantId 租户 ID
     * @param fileSize 上传文件大小（字节）
     * @return true: 有配额；false: 超限
     * @throws ResponseStatusException 403 超限时抛出
     */
    public boolean checkStorageQuota(String tenantId, long fileSize) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        synchronized (tenant) {
            long usage = tenant.getStorageUsage();
            long limit = tenant.getStorageLimit();

            if (usage + fileSize > limit) {
                log.warn("[quota] 存储配额超限 tenant={} used={} adding={} limit={}",
                    tenantId, usage, fileSize, limit);
                auditService.log(tenantId, null, "system", "QUOTA_EXCEEDED", "STORAGE", tenantId,
                    String.format("存储空间不足：当前 %d 字节，上传 %d 字节超过上限 %d 字节",
                        usage, fileSize, limit));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "存储空间不足，无法上传文件");
            }

            tenant.setStorageUsage(usage + fileSize);
            tenantRepository.save(tenant);
            return true;
        }
    }

    /**
     * 从配额中扣除文件大小（文件删除时调用）
     */
    public void reduceStorageUsage(String tenantId, long fileSize) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null) {
            synchronized (tenant) {
                long usage = tenant.getStorageUsage();
                tenant.setStorageUsage(Math.max(0, usage - fileSize));
                tenantRepository.save(tenant);
            }
        }
    }

    // ==================== 席位配额 ====================

    /**
     * 检查并消耗席位配额（用户加入租户时调用）
     * @param tenantId 租户 ID
     * @return true: 有配额；false: 超限
     * @throws ResponseStatusException 403 超限时抛出
     */
    public boolean checkSeatsQuota(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        synchronized (tenant) {
            long used = tenant.getSeatsUsed();
            long limit = tenant.getSeatsLimit();

            if (used >= limit) {
                log.warn("[quota] 席位配额超限 tenant={} used={} limit={}", tenantId, used, limit);
                auditService.log(tenantId, null, "system", "QUOTA_EXCEEDED", "SEATS", tenantId,
                    String.format("席位已满：当前 %d 人/上限 %d 人", used, limit));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "席位已满，请联系管理员");
            }

            tenant.setSeatsUsed(used + 1);
            tenantRepository.save(tenant);
            return true;
        }
    }

    /**
     * 释放席位配额（用户离开租户时调用）
     */
    public void releaseSeatsQuota(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null) {
            synchronized (tenant) {
                long used = tenant.getSeatsUsed();
                tenant.setSeatsUsed(Math.max(0, used - 1));
                tenantRepository.save(tenant);
            }
        }
    }

    // ==================== 计量查询 ====================

    /**
     * 查询租户配额状态
     */
    public QuotaStatus getQuotaStatus(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        return new QuotaStatus(
            tenant.getApiCallUsage(),
            tenant.getApiCallLimit(),
            tenant.getStorageUsage(),
            tenant.getStorageLimit(),
            tenant.getSeatsUsed(),
            tenant.getSeatsLimit()
        );
    }

    public record QuotaStatus(
        long apiCallUsage,
        long apiCallLimit,
        long storageUsage,
        long storageLimit,
        long seatsUsed,
        long seatsLimit
    ) {}
}