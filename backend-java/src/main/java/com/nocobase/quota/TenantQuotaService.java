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

    /**
     * 配额增量刷回数据库的间隔（PHASE135）。
     *
     * <p>与配额分钟窗口（{@link #currentMinuteBucket()}）对齐：配额窗口由 Redis
     * 分钟桶 key 的 TTL 自然滚动，数据库侧只需做最终一致的累计快照，无需高频写入。
     */
    private static final long FLUSH_INTERVAL_MS = 60_000L;
    
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
            //
            // PHASE135 ⚠️ 关键修复：Redis 无值时回退 0，**不再回退 DB 的 apiCallUsage**。
            // 数据库该字段已改为「累计调用快照」（移除了每分钟全表清零），若仍回退 DB 值，
            // 累计值会持续 >= 每分钟的 apiCallLimit，导致 usage >= limit 恒成立、
            // 所有请求被永久 429 —— 与 PHASE92 事故同一机理。
            // Redis 是配额唯一权威源：无值即代表本分钟桶尚未计数，从 0 开始由 INCR 累加。
            String initKey = currentMinuteBucket() + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
            String redisVal = redisTemplate.opsForValue().get(initKey);
            long usage = (redisVal != null) ? Long.parseLong(redisVal) : 0L;

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

        // 3. Redis 原子 INCR（热路径网络操作）；Redis 故障时 fail-safe 拒绝
        //
        // PHASE135 说明：曾评估用 Pipeline 把 INCR+EXPIRE 合并为一次往返，
        // 但单次 RTT 仅约 0.2-0.5ms，相对 50 VU 下 487ms 的 P95 可忽略；
        // 而 Pipeline 需引入 executePipelined/SessionCallback，会显著增加
        // 单测 mock 复杂度。故保持两次调用的简洁实现 —— 本轮 P95 收益来自
        // flush 降频（5s→60s）与移除每分钟全表 reset，而非此处往返合并。
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
     * PHASE135：移除「每分钟全表重置」定时任务。
     *
     * <p>原实现 {@code resetAllApiUsagePeriodically()} 每分钟执行全表
     * {@code UPDATE tenant SET api_call_usage = 0}，在压测期间与业务请求争抢
     * tenant 行锁，是 P95 拉高的锁源之一。
     *
     * <p><b>为何可以安全移除</b>：配额窗口已由 Redis 分钟桶 key 承载 ——
     * key 形如 {@code {minuteBucket}:quota:api:{tenantId}}（见
     * {@link #currentMinuteBucket()}），每分钟自然进入新桶，旧桶 TTL 到期自动失效。
     * 即配额窗口天然滚动，不再需要数据库侧清零来避免「只增不减导致永久封禁」。
     *
     * <p>⚠️ 连带影响（已同步处理）：原方法还负责把 {@code tenantCache} 中各租户的
     * usage 清零。移除后，缓存重建时若仍回退读取数据库累计值，会命中
     * {@code usage >= limit} 而永久 429（PHASE92 事故同一机理）。
     * 因此 {@link #checkAndConsumeApiQuota(String)} 中缓存未命中的回退值已改为 0。
     *
     * <p>如需强制重置单个租户，改用 {@link #resetApiUsage(String)}（手动/管理用）。
     */

    /**
     * 每 60 秒异步刷回 Redis 增量到 DB（最终一致快照）
     *
     * <p><b>PHASE131 优化：</b>改用单条原子 UPDATE（{@code usage = usage + delta}）
     * 替代原先的「findById → setUsage → save」，避免 N+1 往返与方法级长事务。
     *
     * <p><b>PHASE135 优化：</b>间隔由 5 秒放宽到 60 秒，与配额分钟窗口对齐。
     * 5 秒一次的批量 UPDATE 在持续压测下会周期性争抢 tenant 行锁，是 50 VU
     * P95=487ms 的残余锁源；降频后数据库写频率降低 12 倍，锁竞争窗口大幅收窄。
     *
     * <p>语义说明：数据库 {@code api_call_usage} 由「每分钟用量」变为
     * 「累计调用快照（最终一致，延迟 ≤60 秒）」，仅用于审计；配额强制（429 拦截）
     * 仍以 Redis 计数为唯一权威，准确性不受影响。
     *
     * <p>失败补偿：刷回失败的租户其 delta 会重新计入 pendingIncrements，
     * 下一轮重试，避免计数静默丢失。
     */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
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
                tenantRepository.incrementApiUsage(tenantId, delta);
                successCount++;
            } catch (Exception e) {
                log.warn("[quota] 刷回 DB 失败 tenant={} delta={} err={}", tenantId, delta, e.toString());
                // 失败补偿：把 delta 还回 pending，下一轮重试
                pendingIncrements.computeIfAbsent(tenantId, k -> new AtomicLong()).addAndGet(delta);
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

        // 从 Redis 获取最新 usage；无值回退 0（PHASE135：与热路径一致，
        // DB 的 apiCallUsage 已改为累计快照，不可当作当前分钟用量回退）
        String redisKey = currentMinuteBucket() + ":" + REDIS_API_QUOTA_KEY_PREFIX + tenantId;
        String redisVal = redisTemplate.opsForValue().get(redisKey);
        long apiUsage = (redisVal != null) ? Long.parseLong(redisVal) : 0L;

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
