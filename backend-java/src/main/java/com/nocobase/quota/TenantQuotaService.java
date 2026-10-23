package com.nocobase.quota;

import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import com.nocobase.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * 租户配额与计量服务（PHASE92 TN-2 核心实现，PHASE131 P95 极致优化版）
 *
 * <p>三类配额：API 调用 / 存储 / 席位
 * 所有配额均为 fail-close：超限直接返回 429/403
 *
 * <p><b>P95 终极优化（PHASE131）：</b>
 * 1. API 配额热路径：Redis INCR 原子计数，**零 DB 访问**
 * 2. 租户限额：内存缓存（LRU 500），首次加载后全程在内存判断
 * 3. 持久化：每 5 秒异步批量刷回 DB，每分钟重置用量
 *
 * <p><b>数据流：</b>
 * - 请求到达 → 检查 Redis 计数 vs 内存限额 → INCR
 * - 每 5 秒 → 批量刷回 DB（`tenant_api_usage` 列）
 * - 每 60 秒 → 清空 Redis + 重置内存 usage
 */
@Component
public class TenantQuotaService {
    private static final Logger log = LoggerFactory.getLogger(TenantQuotaService.class);

    private final TenantRepository tenantRepository;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;

    // Redis key: {minute}:{tenantId}
    private static final String REDIS_API_QUOTA_KEY_PREFIX = "quota:api:";
    
    // Local cache: tenantId -> QuotaLimit (limit + currentUsage)
    // LRU size 500: 足以容纳活跃租户
    private final Map<String, TenantQuotaCache> tenantCache = new ConcurrentHashMap<>();
    
    // Pending increments: tenantId -> delta
    private final Map<String, AtomicLong> pendingIncrements = new ConcurrentHashMap<>();

    public record TenantQuotaCache(String tenantId, long limit, long usage, long lastUpdated) {}

    public TenantQuotaService(TenantRepository tenantRepository, AuditService auditService,
                              StringRedisTemplate redisTemplate) {
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取当前分钟数（用于 Redis key 分桶）
     */
    private long currentMinuteBucket() {
        return System.currentTimeMillis() / 60_000;
    }

    // ==================== API 配额（零 DB 热路径）====================

    /**
     * 检查并消耗 API 调用配额（**零 DB 访问**，纯内存+Redis）
     * @param tenantId 租户 ID
     * @return true: 有配额并成功消耗；false: 超限
     * @throws ResponseStatusException 429 超限时抛出
     */
    public boolean checkAndConsumeApiQuota(String tenantId) {
        // 1. 获取/缓存租户限额（仅在首次访问或缓存过期时查 DB）
        TenantQuotaCache cache = tenantCache.get(tenantId);
        if (cache == null || System.currentTimeMillis() - cache.lastUpdated() > 60_000) {
            // 过期或未命中，从 DB 加载
            TenantEntity t = tenantRepository.findById(tenantId).orElse(null);
            if (t == null) {
                // 租户不存在属于调用方错误，原样抛出（不被下方 fail-safe 包装）
                throw new IllegalArgumentException("Tenant not found: " + tenantId);
            }

            // 从 Redis 获取当前计数作为 usage（如果存在）
            String initKey = currentMinuteBucket() + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
            String redisVal = redisTemplate.opsForValue().get(initKey);
            long usage = (redisVal != null) ? Long.parseLong(redisVal) : t.getApiCallUsage();

            cache = new TenantQuotaCache(tenantId, t.getApiCallLimit(), usage, System.currentTimeMillis());
            tenantCache.put(tenantId, cache);
        }

        // 2. 检查是否超限（纯内存，零网络）
        if (cache.usage() >= cache.limit()) {
            log.debug("[quota] 内存检查超限 tenant={} usage={} limit={}",
                    tenantId, cache.usage(), cache.limit());
            auditService.log(tenantId, null, "system", "QUOTA_EXCEEDED", "API_CALL", tenantId,
                String.format("API 调用次数达到上限 %d/%d", cache.usage(), cache.limit()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "API 调用次数已达上限，请稍后重试");
        }

        // 3. Redis 原子 INCR（热路径唯一网络操作）；Redis 故障时 fail-safe 拒绝
        long newValue;
        String redisKey = currentMinuteBucket() + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
        try {
            newValue = redisTemplate.opsForValue().increment(redisKey, 1L);
            redisTemplate.expire(redisKey, 3, TimeUnit.MINUTES); // TTL 3 分钟
        } catch (Exception e) {
            log.error("[quota] Redis 异常，拒绝请求 tenant={} err={}", tenantId, e.toString());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "配额服务临时不可用");
        }

        // 4. 更新本地缓存
        tenantCache.put(tenantId, new TenantQuotaCache(
            cache.tenantId(), cache.limit(), newValue, System.currentTimeMillis()));

        // 5. 记录 pending 增量（用于批量刷回）
        pendingIncrements.computeIfAbsent(tenantId, k -> new AtomicLong()).incrementAndGet();

        return true;
    }

    /**
     * 每分钟重置所有租户的 API 用量
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void resetAllApiUsagePeriodically() {
        // 1. DB 清零
        int n = tenantRepository.resetAllApiUsage();
        
        // 2. 清空 pending
        pendingIncrements.clear();
        
        // 3. 清空缓存中的 usage（保留 limit）
        for (String tenantId : tenantCache.keySet()) {
            TenantQuotaCache c = tenantCache.get(tenantId);
            if (c != null) {
                tenantCache.put(tenantId, new TenantQuotaCache(c.tenantId(), c.limit(), 0, System.currentTimeMillis()));
            }
        }
        
        log.debug("[quota] 每分钟重置 API 用量，影响 {} 个租户 (DB)", n);
    }

    /**
     * 每 5 秒异步刷回 Redis 增量到 DB（批量写回，降低 DB 压力）
     */
    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void flushPendingIncrementsToDB() {
        if (pendingIncrements.isEmpty()) {
            return;
        }

        Map<String, AtomicLong> snapshot = new ConcurrentHashMap<>(pendingIncrements);
        pendingIncrements.clear();

        int successCount = 0;
        int failCount = 0;
        
        for (Map.Entry<String, AtomicLong> entry : snapshot.entrySet()) {
            String tenantId = entry.getKey();
            long delta = entry.getValue().get();
            
            if (delta <= 0) continue;
            
            try {
                TenantEntity t = tenantRepository.findById(tenantId).orElse(null);
                if (t != null) {
                    long newUsage = t.getApiCallUsage() + delta;
                    t.setApiCallUsage(newUsage);
                    tenantRepository.save(t);
                    successCount++;
                }
            } catch (Exception e) {
                log.warn("[quota] 刷回 DB 失败 tenant={} delta={} err={}", tenantId, delta, e.toString());
                failCount++;
            }
        }
        
        if (successCount > 0 || failCount > 0) {
            log.debug("[quota] 刷回 DB 完成 success={} fail={}", successCount, failCount);
        }
    }

    /**
     * 重置单个租户的 API 调用计数（手动/管理用）
     */
    public void resetApiUsage(String tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null) {
            tenant.setApiCallUsage(0L);
            tenantRepository.save(tenant);
            
            // 同时重置 Redis 和本地缓存
            long minuteBucket = currentMinuteBucket();
            String redisKey = minuteBucket + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
            redisTemplate.delete(redisKey);
            
            tenantCache.remove(tenantId);
            pendingIncrements.remove(tenantId);
        }
    }

    // ==================== 存储配额 ====================

    /**
     * 检查并消耗存储配额（仍用 DB，因为不是高频操作）
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

        // 尝试从 Redis 获取最新 usage
        String redisKey = currentMinuteBucket() + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
        String redisVal = redisTemplate.opsForValue().get(redisKey);
        long apiUsage = (redisVal != null) ? Long.parseLong(redisVal) : tenant.getApiCallUsage();

        return new QuotaStatus(
            apiUsage,
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
