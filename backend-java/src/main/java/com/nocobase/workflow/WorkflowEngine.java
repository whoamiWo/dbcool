package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public enum NodeResult { CONTINUE, NEEDS_APPROVAL, FAILED }

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRepository workflowRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public WorkflowEngine(
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository,
            WorkflowRepository workflowRepository,
            MessageRepository messageRepository,
            ObjectMapper objectMapper
    ) {
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.workflowRepository = workflowRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步执行从 startIdx 开始的节点,遇到 APPROVAL 暂停并返回。
     * 已经处理过的节点不再执行(条件分支跳过也算)。
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

            if ("APPROVAL".equals(nodeType)) {
                createApprovalTask(instance.getId(), (String) node.get("id"), defaultAssignee);
                instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                instanceRepository.save(instance);
                return NodeResult.NEEDS_APPROVAL;
            } else if ("NOTIFICATION".equals(nodeType)) {
                logNotification(instance, node);
                i++;
            } else if ("CONDITION".equals(nodeType)) {
                // 条件节点:评估 then/else,跳到下一个要执行的节点
                int nextIdx = evaluateCondition(instance, node, nodes, i);
                if (nextIdx < 0) {
                    // then/else 路径都失败或都不存在 → 完成
                    return NodeResult.FAILED;
                }
                i = nextIdx;
            } else if ("HTTP".equals(nodeType)) {
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
