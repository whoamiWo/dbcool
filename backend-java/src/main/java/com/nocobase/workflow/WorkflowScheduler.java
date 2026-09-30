package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 定时触发调度器 (Phase 60 T3-2: 去内存态 + Cron 支持).
 *
 * <p>特性:
 * <ul>
 *   <li>持久化 last_triggered_at 到数据库，重启不重置</li>
 *   <li>Cron 表达式支持 (优先级高于 intervalMinutes)</li>
 *   <li>分布式锁 (Redis SETNX + TTL) 防止多实例重复触发</li>
 *   <li>非法 cron 明确报错</li>
 * </ul>
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate;

    /** Redis 锁前缀 */
    private static final String LOCK_PREFIX = "workflow:schedule:lock:";

    /** 锁超时时间 (秒),略大于最大调度间隔以防死锁 */
    private static final long LOCK_TTL_SECONDS = 3600;

    public WorkflowScheduler(WorkflowRepository workflowRepository, WorkflowEngine engine,
                             StringRedisTemplate redisTemplate) {
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.redisTemplate = redisTemplate;
    }

    /** 每 60 秒检查一次;首次延迟 30 秒，避开启动期 bean 初始化。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void pollScheduledWorkflows() {
        for (WorkflowEntity w : workflowRepository.findAll()) {
            if (!w.isEnabled()) continue;

            // 尝试获取分布式锁
            if (!tryAcquireLock(w.getId())) {
                log.debug("工作流 {} 已被其他实例锁定，跳过", w.getName());
                continue;
            }

            try {
                // 优先解析 cron，其次 intervalMinutes
                Instant now = Instant.now();
                boolean triggered = false;

                CronSchedule cronSchedule = parseCron(w.getTriggerJson());
                if (cronSchedule != null) {
                    triggered = handleCronSchedule(w, cronSchedule, now);
                } else {
                    Integer interval = parseIntervalMinutes(w.getTriggerJson());
                    if (interval != null) {
                        triggered = handleIntervalSchedule(w, interval, now);
                    }
                }

                if (triggered) {
                    trigger(w);
                    updateLastTriggered(w.getId(), now);
                }
            } catch (IllegalArgumentException e) {
                log.error("工作流 {} 触发配置非法: {}", w.getName(), e.getMessage());
            } finally {
                releaseLock(w.getId());
            }
        }
    }

    /** 尝试获取分布式锁 (SETNX + TTL) */
    private boolean tryAcquireLock(UUID workflowId) {
        String lockKey = LOCK_PREFIX + workflowId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /** 释放分布式锁 */
    private void releaseLock(UUID workflowId) {
        String lockKey = LOCK_PREFIX + workflowId;
        redisTemplate.delete(lockKey);
    }

    /** 更新数据库中 last_triggered_at */
    private void updateLastTriggered(UUID workflowId, Instant now) {
        workflowRepository.findById(workflowId).ifPresent(w -> {
            w.setLastTriggeredAt(now);
            workflowRepository.save(w);
        });
    }

    /** 处理 Cron 调度 */
    private boolean handleCronSchedule(WorkflowEntity w, CronSchedule cronSchedule, Instant now) {
        Instant lastTriggered = w.getLastTriggeredAt();
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime nowZoned = now.atZone(zone);
        if (lastTriggered == null) {
            ZonedDateTime nextZoned = cronSchedule.nextZoned(nowZoned.minusSeconds(1));
            if (nextZoned != null && nowZoned.isAfter(nextZoned.toInstant().atZone(zone))) {
                log.info("工作流 {} 首次触发，下一个触发点 {}", w.getName(), nextZoned);
                return true;
            }
            return false;
        }

        ZonedDateTime nextZoned = cronSchedule.nextZoned(lastTriggered.atZone(zone));
        if (nextZoned != null && nowZoned.isAfter(nextZoned.toInstant().atZone(zone))) {
            log.info("工作流 {} Cron 触发，上一个={} 下一个={}", w.getName(), lastTriggered, nextZoned);
            return true;
        }
        return false;
    }

    /** 处理 Interval 调度 (向后兼容) */
    private boolean handleIntervalSchedule(WorkflowEntity w, int intervalMinutes, Instant now) {
        Instant lastTriggered = w.getLastTriggeredAt();
        if (lastTriggered == null) {
            return true;
        }
        return now.isAfter(lastTriggered.plusSeconds(intervalMinutes * 60L));
    }

    /** Cron 表达式解析结果 */
    private static class CronSchedule {
        private final CronExpression expression;

        CronSchedule(CronExpression expression) {
            this.expression = expression;
        }

        Instant next(Instant from) {
            return expression.next(from);
        }

        ZonedDateTime nextZoned(ZonedDateTime from) {
            return expression.next(from);
        }
    }

    /** 解析 Cron 配置;返回 null 表示不是 cron 类型 */
    private CronSchedule parseCron(String triggerJson) {
        if (triggerJson == null || triggerJson.isBlank()) return null;
        try {
            Map<String, Object> t = objectMapper.readValue(triggerJson,
                    new TypeReference<Map<String, Object>>() {});
            if (!"cron".equals(t.get("type"))) return null;
            Object v = t.get("expression");
            if (!(v instanceof String expr)) return null;

            try {
                CronExpression exp = CronExpression.parse(expr);
                return new CronSchedule(exp);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("非法 Cron 表达式：" + expr, e);
            }
        } catch (IllegalArgumentException e) {
            // 非法 cron 明确抛出，调用方需要处理
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 schedule 配置的间隔分钟数;非 schedule 类型返回 null。 */
    private Integer parseIntervalMinutes(String triggerJson) {
        if (triggerJson == null || triggerJson.isBlank()) return null;
        try {
            Map<String, Object> t = objectMapper.readValue(triggerJson,
                    new TypeReference<Map<String, Object>>() {});
            if (!"schedule".equals(t.get("type"))) return null;
            Object v = t.get("intervalMinutes");
            if (v instanceof Number n && n.intValue() > 0) return n.intValue();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 创建实例并执行 (定时触发无关联记录，triggerData ��标注来源)。 */
    private void trigger(WorkflowEntity w) {
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(w.getId());
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instance.setTriggerDataJson("{\"source\":\"schedule\"}");
        instance.setTenantId(w.getTenantId());
        instance.setCurrentNodeIndex(0);

        try {
            List<Map<String, Object>> nodes = objectMapper.readValue(w.getNodesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> edges = objectMapper.readValue(w.getEdgesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});

            WorkflowEngine.NodeResult result;
            if (!edges.isEmpty() && !nodes.isEmpty()) {
                result = engine.executeGraphFrom(instance, nodes, edges,
                        (String) nodes.get(0).get("id"), w.getCreatedBy());
            } else {
                result = engine.executeFrom(instance, nodes, 0, w.getCreatedBy());
            }
            log.info("工作流 {} 触发完成，结果 {}", w.getName(), result);
        } catch (Exception e) {
            log.error("工作流 {} 执行失败：{}", w.getName(), e.getMessage(), e);
        }
    }
}