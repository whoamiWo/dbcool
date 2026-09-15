package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发调度器(Week 41 复核 D4a 收尾)。
 *
 * <p>此前 D4a 只实现了数据变更触发(on_create / on_update / on_delete),
 * <strong>定时触发完全缺失</strong> —— 前端虽有"⏰ 定时调度"选项,后端却没有任何调度器,
 * 选了它等同于不触发。
 *
 * <p>触发配置({@code workflows.trigger_json}):
 * <pre>{@code
 * { "type": "schedule", "intervalMinutes": 60 }
 * }</pre>
 *
 * <p><strong>已知限制</strong>:上次触发时间记在内存,重启后会重置(即重启后立刻触发一次);
 * 多实例部署会重复触发。生产环境需改为持久化 + 分布式锁(Week 42+)。
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** workflowId → 上次触发时间(内存态,重启重置)。 */
    private final Map<UUID, Instant> lastTriggered = new ConcurrentHashMap<>();

    public WorkflowScheduler(WorkflowRepository workflowRepository, WorkflowEngine engine) {
        this.workflowRepository = workflowRepository;
        this.engine = engine;
    }

    /** 每 60 秒检查一次;首次延迟 30 秒,避开启动期 bean 初始化。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void pollScheduledWorkflows() {
        for (WorkflowEntity w : workflowRepository.findAll()) {
            if (!w.isEnabled()) continue;
            Integer interval = parseIntervalMinutes(w.getTriggerJson());
            if (interval == null) continue;
            if (!due(w.getId(), interval)) continue;
            trigger(w);
            lastTriggered.put(w.getId(), Instant.now());
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

    private boolean due(UUID workflowId, int intervalMinutes) {
        Instant last = lastTriggered.get(workflowId);
        if (last == null) return true;
        return Instant.now().isAfter(last.plusSeconds(intervalMinutes * 60L));
    }

    /** 创建实例并执行(定时触发无关联记录,triggerData 仅标注来源)。 */
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
            log.info("D4a schedule: 工作流 {} 触发完成,结果 {}", w.getName(), result);
        } catch (Exception e) {
            log.error("D4a schedule: 工作流 {} 执行失败: {}", w.getName(), e.getMessage(), e);
        }
    }
}
