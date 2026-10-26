package com.nocobase.integration.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * jsapi_ticket 统一缓存（按 租户 + provider + type 维度隔离）。
 *
 * <p>为什么必须缓存：各平台 jsapi_ticket 有效期 7200s，且**有严格调用频率限制**，
 * 每次页面加载都实时拉取会很快触发限流导致签名失败。缓存 TTL 取 7000s，
 * 比官方 7200s 提前 200s 失效，避免临界期拿到已过期 ticket。
 *
 * <p>Redis 不可用时降级为"直接拉取"（不阻断业务），与 BI 缓存策略一致。
 */
@Component
public class JsTicketCache {

    private static final Logger log = LoggerFactory.getLogger(JsTicketCache.class);

    private static final String CACHE_PREFIX = "js:ticket:";
    /** 官方有效期 7200s，缓存 7000s 留 200s buffer */
    private static final Duration CACHE_TTL = Duration.ofSeconds(7000);

    private final StringRedisTemplate redis;

    public JsTicketCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String tenantId, String provider, String type) {
        return CACHE_PREFIX + provider + ":" + (tenantId == null ? "global" : tenantId) + ":" + type;
    }

    public Optional<String> get(String tenantId, String provider, String type) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(tenantId, provider, type)));
        } catch (Exception e) {
            log.warn("[js-ticket] 读取缓存失败，降级为直接拉取 provider={} type={}", provider, type, e);
            return Optional.empty();
        }
    }

    public void put(String tenantId, String provider, String type, String ticket) {
        try {
            redis.opsForValue().set(key(tenantId, provider, type), ticket, CACHE_TTL);
        } catch (Exception e) {
            log.warn("[js-ticket] 写入缓存失败，不影响主流程 provider={} type={}", provider, type, e);
        }
    }

    /** 主动失效（用于凭证变更后强制刷新或管理端点清缓存）。 */
    public void evict(String tenantId, String provider, String type) {
        try {
            redis.delete(key(tenantId, provider, type));
        } catch (Exception e) {
            log.warn("[js-ticket] 清除缓存失败 provider={} type={}", provider, type, e);
        }
    }
}
