package com.nocobase.workflow;

import com.nocobase.event.RecordChangeEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 工作流触发器监听器(Week 41 D4a — 触发器真实化).
 *
 * <p>订阅 RecordChangeEvent,根据 trigger_json.type 找到匹配的工作流并执行。
 *
 * <p>异步执行(简单 future 池,Week 42+ 可换 Redis Streams):
 * <ul>
 *   <li>调用 WorkflowController 风格的 trigger 逻辑(创建实例 + 引擎执行)</li>
 *   <li>目前为同步执行,简单安全</li>
 *   <li>未来可加 @Async 注解 + ThreadPoolTaskExecutor</li>
 * </ul>
 *
 * <p>死循环防护(报告 C-R04):同一 record_id 在 1 分钟内最多触发 N 次,
 * 本 Step G2 暂未实现,留给 D4a.3。
 */
@Component
public class WorkflowTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerListener.class);

    private final WorkflowTriggerMatcher matcher;
    private final WorkflowEngine engine;
    private final WorkflowRepository workflowRepository;
    /** Week 41 D4a.3:触发器频率限制(防死循环,报告 C-R04)。 */
    private final TriggerRateLimiter rateLimiter;

    public WorkflowTriggerListener(
            WorkflowTriggerMatcher matcher,
            WorkflowEngine engine,
            WorkflowRepository workflowRepository,
            TriggerRateLimiter rateLimiter
    ) {
        this.matcher = matcher;
        this.engine = engine;
        this.workflowRepository = workflowRepository;
        this.rateLimiter = rateLimiter;
    }

    @EventListener
    public void onRecordChange(RecordChangeEvent event) {
        try {
            List<WorkflowEntity> matches = matcher.findMatching(event);
            if (matches.isEmpty()) return;

            log.info("D4a: 收到 {} 事件,匹配 {} 个工作流 (collection={})",
                    event.getChangeType(), matches.size(), event.getCollectionName());

            for (WorkflowEntity w : matches) {
                // Week 41 D4a.3:rate limit 防死循环(报告 C-R04)
                if (!rateLimiter.allowTrigger(w.getId().toString(), event.getRecordId())) {
                    log.warn("D4a: 工作流 {} 触发器频率超限,recordId={} 被阻断(防死循环)",
                            w.getName(), event.getRecordId());
                    continue;
                }
                triggerWorkflow(w, event);
            }
        } catch (Exception e) {
            // 监听器异常不应影响主流程(CollectionController 已返回响应)
            log.error("D4a 触发器监听器异常: {}", e.getMessage(), e);
        }
    }

    /** 触发单个工作流(创建实例 + 引擎执行)。 */
    private void triggerWorkflow(WorkflowEntity w, RecordChangeEvent event) {
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(w.getId());
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instance.setTriggerDataJson(toJson(event));
        instance.setRecordId(event.getRecordId());
        instance.setTenantId(event.getTenantId());
        instance.setCurrentNodeIndex(0);

        try {
            // 直接调引擎(简化版 trigger),跳过 controller 复杂参数解析
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.Map<String, Object>> nodes = mapper.readValue(
                    w.getNodesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
            java.util.List<java.util.Map<String, Object>> edges = mapper.readValue(
                    w.getEdgesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});

            WorkflowEngine.NodeResult result;
            if (!edges.isEmpty() && !nodes.isEmpty()) {
                String startId = (String) nodes.get(0).get("id");
                result = engine.executeGraphFrom(instance, nodes, edges, startId, w.getCreatedBy());
            } else {
                result = engine.executeFrom(instance, nodes, 0, w.getCreatedBy());
            }

            log.info("D4a: 工作流 {} 触发 {} 状态 {}",
                    w.getName(), event.getChangeType(), result);
        } catch (Exception e) {
            log.error("D4a: 工作流 {} 执行失败: {}", w.getName(), e.getMessage(), e);
        }
    }

    /** 把 event 转成 triggerData JSON。 */
    private String toJson(RecordChangeEvent event) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of(
                            "changeType", event.getChangeType().toString(),
                            "collection", event.getCollectionName(),
                            "record_id", event.getRecordId(),
                            "data", event.getData() != null ? event.getData() : java.util.Map.of()
                    ));
        } catch (Exception e) {
            return "{}";
        }
    }
}
