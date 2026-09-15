package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.notification.NotificationService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 工作流执行引擎(US-405 条件分支 + US-409 HTTP 节点).
 *
 * <p>设计:
 * <ul>
 *   <li>APPROVAL → 创建 PENDING task,实例暂停(返回 NEEDS_APPROVAL)</li>
 *   <li>NOTIFICATION → 简化为 log,继续下一个</li>
 *   <li>CONDITION → 根据字段值选 then/else 分支,跳过不执行的分支</li>
 *   <li>HTTP → 调外部 API,支持 Bearer/Basic,继续下一个</li>
 * </ul>
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** Week 42 D4b.2 (R08 死循环防护) — 单次 workflow 执行的最大栈深度。
     *  超过则终止并标记 instance 失败,防止 condition 分支 + 嵌套 handler 形成递归栈过深。
     *  静态 DAG 校验由 {@link WorkflowGraphValidator} 完成,本常量是运行时兜底。
     */
    static final int MAX_EXECUTION_DEPTH = 50;

    /** Null-safe 大小写不敏感比较(避免上游传 null 节点类型时 NPE)。Week 40 B1 修复。 */
    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null) return false;
        return a.equalsIgnoreCase(b);
    }

    public enum NodeResult { CONTINUE, NEEDS_APPROVAL, FAILED }

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRepository workflowRepository;
    private final MessageRepository messageRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    /** Week 41 D4b.1:节点策略化注册表。 */
    private final WorkflowNodeHandlerRegistry handlerRegistry;

    public WorkflowEngine(
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository,
            WorkflowRepository workflowRepository,
            MessageRepository messageRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper,
            WorkflowNodeHandlerRegistry handlerRegistry
    ) {
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.workflowRepository = workflowRepository;
        this.messageRepository = messageRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * 同步执行从 startNodeId 开始的节点(图遍历),遇到 APPROVAL 暂停并返回。
     * <p>Week 14:支持边驱动的图执行;起点由上游传入(无入边的节点或 trigger 指定)。
     * <p>顺序默认 fallback 到 {@link #executeFrom}(老数组模式)。
     */
    @Transactional
    public NodeResult executeGraphFrom(
            WorkflowInstanceEntity instance,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String startNodeId,
            UUID defaultAssignee
    ) {
        // 公开入口 — depth = 0
        return executeGraphFrom(instance, nodes, edges, startNodeId, defaultAssignee, 0);
    }

    /** 内部重载:跟踪当前执行栈深度,R08 死循环防护运行时兜底。 */
    NodeResult executeGraphFrom(
            WorkflowInstanceEntity instance,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            String startNodeId,
            UUID defaultAssignee,
            int depth
    ) {
        // node id → Map
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> n : nodes) {
            String id = (String) n.get("id");
            if (id != null) byId.put(id, n);
        }
        // source → list of (target, sourceHandle)
        Map<String, List<Map<String, Object>>> out = new java.util.HashMap<>();
        for (Map<String, Object> e : edges) {
            String src = (String) e.get("source");
            out.computeIfAbsent(src, k -> new java.util.ArrayList<>())
               .add(Map.of("target", String.valueOf(e.get("target")),
                            "handle", String.valueOf(e.getOrDefault("sourceHandle", ""))));
        }
        Set<String> visited = new java.util.HashSet<>();
        String current = startNodeId;
        while (current != null) {
            if (depth >= MAX_EXECUTION_DEPTH) {
                // 超过栈深度 — 标记 instance 失败并退出
                instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
                instance.setErrorMessage("执行栈深度超限(>= " + MAX_EXECUTION_DEPTH + "),违反 R08 死循环防护");
                instanceRepository.save(instance);
                log.error("workflow {} execution depth exceeded ({}), terminated", instance.getId(), depth);
                return NodeResult.FAILED;
            }
            if (!visited.add(current)) {
                log.warn("workflow {} cycle detected at {}", instance.getId(), current);
                break;
            }
            Map<String, Object> node = byId.get(current);
            if (node == null) {
                log.warn("workflow {} node {} not found", instance.getId(), current);
                break;
            }
            instance.setCurrentNodeIndex(nodes.indexOf(node));
            String nodeType = (String) node.get("type");
            String next = null;
            String[] handleHolder = {null}; // 用 array 持有 handle 状态(lambda 用 final)
            // Week 41 复核修复:策略化分发提到最前面(原先挂在 4 个内置分支之后,
            // 导致 4 个 handler 永不执行 = 死代码)。
            // 生产:Spring 注入真实内置 handler → 内置节点走 handler;
            // 测试:若注入空 registry → 自动回退下方 legacy 分支,既有测试零改动通过。
            Optional<WorkflowNodeHandler> handler = handlerRegistry.find(nodeType);
            if (handler.isPresent()) {
                NodeExecutionContext nodeCtx = new NodeExecutionContext(
                        instance, node, defaultAssignee, null);
                NodeOutcome outcome = handler.get().execute(nodeCtx);
                if (outcome == NodeOutcome.NEEDS_APPROVAL) {
                    instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                    instanceRepository.save(instance);
                    return NodeResult.NEEDS_APPROVAL;
                }
                if (outcome == NodeOutcome.FAILED) {
                    instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
                    instance.setErrorMessage("Node " + current + " failed");
                    instanceRepository.save(instance);
                    return NodeResult.FAILED;
                }
                // CONTINUE / SKIPPED 继续
                if (outcome == NodeOutcome.SKIPPED) {
                    handleHolder[0] = "false"; // SKIPPED 走 false 分支
                }
                // CONDITION handler 把 matched 写到 node._matched,这里读出
                Object matched = node.get("_matched");
                if (matched instanceof Boolean b) {
                    handleHolder[0] = b ? "true" : "false";
                }
            } else if (equalsIgnoreCase(nodeType, "APPROVAL")) {
                // ↓↓↓ 以下为 legacy 兼容兜底:仅当 registry 未命中时执行
                createApprovalTask(instance.getId(), current, defaultAssignee);
                instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                instanceRepository.save(instance);
                return NodeResult.NEEDS_APPROVAL;
            } else if (equalsIgnoreCase(nodeType, "NOTIFICATION")) {
                logNotification(instance, node);
            } else if (equalsIgnoreCase(nodeType, "CONDITION")) {
                // 评估 then/else,带 sourceHandle = "true" / "false"
                boolean matched = matchCondition(instance, castConfig(castConfig(node.get("config")).get("when")));
                handleHolder[0] = matched ? "true" : "false";
                log.info("workflow {} condition {} matched={}", instance.getId(), current, matched);
            } else if (equalsIgnoreCase(nodeType, "HTTP")) {
                executeHttp(instance, node);
            } else {
                log.warn("workflow {} unknown node type: {}", instance.getId(), nodeType);
            }
            // 找到下一节点
            List<Map<String, Object>> outs = out.getOrDefault(current, List.of());
            if (outs.isEmpty()) break; // 无出边 → 完成
            if (handleHolder[0] != null) {
                // 条件分支:找 sourceHandle 匹配的边
                Map<String, Object> picked = null;
                for (Map<String, Object> o : outs) {
                    if (handleHolder[0].equals(o.get("handle"))) { picked = o; break; }
                }
                if (picked == null) picked = outs.get(0);
                next = (String) picked.get("target");
            } else {
                // 顺序流:取第一个
                next = (String) outs.get(0).get("target");
            }
            current = next;
        }
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        instance.setFinishedAt(java.time.Instant.now());
        instanceRepository.save(instance);
        return NodeResult.CONTINUE;
    }

    /**
     * 同步执行从 startIdx 开始的节点(数组顺序模式,Week 11 旧实现,保留兼容).
     * 遇到 APPROVAL 暂停并返回。
     */
    @Transactional
    public NodeResult executeFrom(
            WorkflowInstanceEntity instance,
            List<Map<String, Object>> nodes,
            int startIdx,
            UUID defaultAssignee
    ) {
        int i = startIdx;
        while (i < nodes.size()) {
            Map<String, Object> node = nodes.get(i);
            instance.setCurrentNodeIndex(i);
            String nodeType = (String) node.get("type");

            // CONDITION 在数组模式保持 legacy:数组语义是"跳到 then/else 索引",
            // 而 handler 只能回传 _matched,无法表达索引跳转 → 不交给策略分发
            if (equalsIgnoreCase(nodeType, "CONDITION")) {
                int nextIdx = evaluateCondition(instance, node, nodes, i);
                if (nextIdx < 0) {
                    // then/else 路径都失败或都不存在 → 完成
                    return NodeResult.FAILED;
                }
                i = nextIdx;
                continue;
            }

            // 其余类型:策略化分发优先,legacy 兜底(同图模式)
            Optional<WorkflowNodeHandler> handler = handlerRegistry.find(nodeType);
            if (handler.isPresent()) {
                NodeExecutionContext nodeCtx = new NodeExecutionContext(
                        instance, node, defaultAssignee, null);
                NodeOutcome outcome = handler.get().execute(nodeCtx);
                if (outcome == NodeOutcome.NEEDS_APPROVAL) {
                    instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                    instanceRepository.save(instance);
                    return NodeResult.NEEDS_APPROVAL;
                }
                if (outcome == NodeOutcome.FAILED) {
                    instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
                    instanceRepository.save(instance);
                    return NodeResult.FAILED;
                }
                i++;
            } else if (equalsIgnoreCase(nodeType, "APPROVAL")) {
                createApprovalTask(instance.getId(), (String) node.get("id"), defaultAssignee);
                instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                instanceRepository.save(instance);
                return NodeResult.NEEDS_APPROVAL;
            } else if (equalsIgnoreCase(nodeType, "NOTIFICATION")) {
                logNotification(instance, node);
                i++;
            } else if (equalsIgnoreCase(nodeType, "HTTP")) {
                executeHttp(instance, node);
                i++;
            } else {
                // 未知节点类型 → 跳过
                log.warn("workflow {} unknown node type: {}", instance.getId(), nodeType);
                i++;
            }
        }
        // 全部跑完
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        instance.setFinishedAt(Instant.now());
        instanceRepository.save(instance);
        return NodeResult.CONTINUE;
    }

    private void createApprovalTask(UUID instanceId, String nodeId, UUID assignee) {
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(UUID.randomUUID());
        task.setInstanceId(instanceId);
        task.setNodeId(nodeId);
        task.setNodeType("APPROVAL");
        task.setAssignee(assignee);
        task.setStatus(WorkflowTaskEntity.Status.PENDING);
        task.setCreatedAt(Instant.now());
        taskRepository.save(task);
    }

    private void logNotification(WorkflowInstanceEntity instance, Map<String, Object> node) {
        Map<String, Object> cfg = castConfig(node.get("config"));
        String title = (String) cfg.getOrDefault("title", "通知");
        String body = (String) cfg.getOrDefault("message", "(no message)");
        Object recipientObj = cfg.get("recipient");
        log.info("[workflow {} node {} notification] {}", instance.getId(), node.get("id"), body);

        // 收件人:config.recipient(用户 UUID 字符串) 或 workflow 创建者
        UUID recipient = instanceRepository.findById(instance.getId()).map(i ->
                workflowRepository.findById(i.getWorkflowId())
                        .map(w -> w.getCreatedBy()).orElse(null)
        ).orElse(null);
        if (recipientObj instanceof String s && !s.isBlank()) {
            try { recipient = UUID.fromString(s); } catch (Exception ignored) {}
        }
        if (recipient == null) return;

        // 1) InApp 站内信(原有)
        MessageEntity msg = new MessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setRecipient(recipient);
        msg.setType("workflow");
        msg.setTitle(title);
        msg.setBody(body);
        msg.setRelatedId(instance.getId().toString());
        msg.setCreatedAt(java.time.Instant.now());
        msg.setTenantId(instance.getTenantId());
        messageRepository.save(msg);

        // 2) 多渠道通知(Week 14.5 P3 — 新)
        // event 格式:workflow.<type> e.g. workflow.notification
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            payload.put("data", Map.of(
                    "instance_id", instance.getId().toString(),
                    "node_id", String.valueOf(node.get("id")),
                    "workflow_id", instance.getWorkflowId().toString()
            ));
            String recipientStr = recipient.toString();
            // events match in channel config: "workflow.*" or 全部事件
            notificationService.fire(instance.getTenantId(), "workflow.notification",
                    recipientStr, payload);
        } catch (Exception e) {
            log.warn("notify fire failed: {}", e.getMessage());
        }
    }

    /**
     * 条件节点评估:
     * config: {when: {field, op, value}, then: <node_id>, else: <node_id>}
     * 返回 then 或 else 节点的下一个索引;如果两边都未找到返回 -1。
     */
    private int evaluateCondition(
            WorkflowInstanceEntity instance,
            Map<String, Object> node,
            List<Map<String, Object>> nodes,
            int currentIdx
    ) {
        Map<String, Object> cfg = castConfig(node.get("config"));
        Map<String, Object> when = castConfig(cfg.get("when"));
        String targetNodeId = matchCondition(instance, when)
                ? (String) cfg.get("then")
                : (String) cfg.get("else");

        if (targetNodeId == null) {
            log.warn("workflow {} condition: no matching branch", instance.getId());
            return currentIdx + 1;
        }

        for (int j = currentIdx + 1; j < nodes.size(); j++) {
            if (targetNodeId.equals(nodes.get(j).get("id"))) {
                return j; // 让 executeFrom 的 i++ 跳到 j+1
            }
        }
        log.warn("workflow {} condition: target node {} not found",
                instance.getId(), targetNodeId);
        return currentIdx + 1;
    }

    /**
     * 简单的条件匹配:从 triggerData 拿字段值,与 value 比较。
     * 支持 op: eq / neq / contains / gt / lt。
     */
    private boolean matchCondition(WorkflowInstanceEntity instance, Map<String, Object> when) {
        if (when == null) return true;
        Map<String, Object> data = parseTriggerData(instance.getTriggerDataJson());
        String field = (String) when.get("field");
        String op = (String) when.getOrDefault("op", "eq");
        Object expected = when.get("value");
        Object actual = data == null ? null : data.get(field);
        if (actual == null) return false;
        switch (op) {
            case "eq": return String.valueOf(actual).equals(String.valueOf(expected));
            case "neq": return !String.valueOf(actual).equals(String.valueOf(expected));
            case "contains": return String.valueOf(actual).contains(String.valueOf(expected));
            case "gt": return toDouble(actual) > toDouble(expected);
            case "lt": return toDouble(actual) < toDouble(expected);
            default: return false;
        }
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    /**
     * HTTP 节点:支持 method(POST/GET/PUT/DELETE)、url、headers、body。
     * 鉴权:config.auth.type = bearer | basic | none。
     */
    private void executeHttp(WorkflowInstanceEntity instance, Map<String, Object> node) {
        Map<String, Object> cfg = castConfig(node.get("config"));
        String method = (String) cfg.getOrDefault("method", "POST");
        String url = (String) cfg.get("url");
        Object body = cfg.get("body");
        Map<String, String> headers = castStringMap(cfg.get("headers"));

        if (url == null || url.isBlank()) {
            log.warn("workflow {} http node missing url", instance.getId());
            return;
        }

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach(httpHeaders::set);
            }

            // 鉴权
            Map<String, Object> auth = castConfig(cfg.get("auth"));
            if (auth != null) {
                String authType = (String) auth.getOrDefault("type", "none");
                if ("bearer".equals(authType)) {
                    httpHeaders.setBearerAuth((String) auth.getOrDefault("token", ""));
                } else if ("basic".equals(authType)) {
                    String username = (String) auth.getOrDefault("username", "");
                    String password = (String) auth.getOrDefault("password", "");
                    httpHeaders.setBasicAuth(username, password);
                }
            }

            HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
            ResponseEntity<String> resp = restTemplate.exchange(url, httpMethod, entity, String.class);
            log.info("[workflow {} http node {}] {} → {}",
                    instance.getId(), node.get("id"), method, resp.getStatusCode().value());
        } catch (RestClientException e) {
            log.error("workflow {} http node {} failed: {}",
                    instance.getId(), node.get("id"), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castConfig(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> castStringMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, String> r = new HashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return r;
        }
        return null;
    }

    private Map<String, Object> parseTriggerData(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
