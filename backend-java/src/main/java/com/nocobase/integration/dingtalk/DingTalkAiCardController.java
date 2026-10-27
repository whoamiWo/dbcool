package com.nocobase.integration.dingtalk;

import com.nocobase.audit.AuditService;
import com.nocobase.integration.common.ExternalMessageLogEntity;
import com.nocobase.integration.common.ExternalMessageLogRepository;
import com.nocobase.tenant.TenantContext;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 钉钉 AI 卡片 REST API(B4)。
 *
 * <p><b>出站</b>:{@code POST /api/dingtalk/ai-card/send} — 投递一张带 AI 摘要的交互卡片。
 * <p><b>入站</b>:{@code POST /api/dingtalk/ai-card/callback} — 卡片按钮回调,
 * HMAC 签名校验 + {@code external_message_log} UNIQUE 幂等,支持 {@code open_url} 与
 * {@code workflow_action}(联动工作流任务同意/拒绝)。
 */
@RestController
@RequestMapping("/api/dingtalk/ai-card")
public class DingTalkAiCardController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAiCardController.class);

    private final DingTalkAiCardService aiCardService;
    private final DingTalkAppService appService;
    private final WorkflowTaskRepository taskRepository;
    private final AuditService auditService;

    @Autowired
    private ExternalMessageLogRepository externalMessageLogRepository;

    public DingTalkAiCardController(DingTalkAiCardService aiCardService,
                                    DingTalkAppService appService,
                                    WorkflowTaskRepository taskRepository,
                                    AuditService auditService) {
        this.aiCardService = aiCardService;
        this.appService = appService;
        this.taskRepository = taskRepository;
        this.auditService = auditService;
    }

    /**
     * 投递 AI 卡片。
     *
     * <p>Body: {@code {title, content, targetUserId, openUrl?, workflowTaskId?, workflowAction?}}
     */
    @PostMapping("/send")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        String title = str(body.get("title"));
        String content = str(body.get("content"));
        String targetUserId = str(body.get("targetUserId"));
        String openUrl = str(body.get("openUrl"));

        if (title.isBlank() || targetUserId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "title 与 targetUserId 必填"));
        }

        Map<String, Object> workflowAction = null;
        if (body.get("workflowTaskId") != null) {
            workflowAction = Map.of(
                    "taskId", str(body.get("workflowTaskId")),
                    "action", "REJECT".equalsIgnoreCase(str(body.get("workflowAction")))
                            ? "REJECT" : "APPROVE");
        }

        DingTalkAiCardService.SendCardResult result =
                aiCardService.sendCard(title, content, targetUserId, openUrl, workflowAction);

        auditService.log(TenantContext.currentTenantId(), "system", "system",
                "dingtalk.ai-card.send", "dingtalk_ai_card",
                targetUserId, Map.of("success", result.success(),
                        "detail", result.detail() == null ? "" : result.detail()));

        if (!result.success()) {
            return ResponseEntity.status(502)
                    .body(Map.of("code", 502, "message", result.detail()));
        }
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "sent",
                "data", Map.of("outTrackId", result.outTrackId())));
    }

    /**
     * 卡片按钮回调。
     *
     * <p>Header: {@code x-dingtalk-timestamp} + {@code x-dingtalk-signature}。
     * Body: {@code {eventId, action, workflowTaskId?, workflowResult?, url?}}。
     *
     * <p>幂等:{@code external_message_log} UNIQUE(source, external_message_id) 兜底并发。
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(value = "x-dingtalk-timestamp", required = false) String timestamp,
            @RequestHeader(value = "x-dingtalk-signature", required = false) String signature,
            @RequestBody Map<String, Object> body) {

        // 1. HMAC 签名校验(与审批回调共用工具)
        String appSecret = appService.getAppSecret();
        if (appSecret == null || appSecret.isBlank()) {
            log.warn("[DingTalkAiCard] 回调：appSecret 未配置");
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "unauthorized"));
        }
        if (!DingTalkSignatureUtil.verify(appSecret, timestamp, signature)) {
            log.warn("[DingTalkAiCard] 回调：签名校验失败 ts={}", timestamp);
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "invalid signature"));
        }

        // 2. 幂等
        String eventId = str(body.get("eventId"));
        if (eventId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "eventId 必填"));
        }
        String tenantId = TenantContext.currentTenantId();
        if (externalMessageLogRepository
                .findBySourceAndExternalMessageId("dingtalk_aicard", eventId).isPresent()) {
            log.info("[DingTalkAiCard] 回调重复,忽略 eventId={}", eventId);
            return ResponseEntity.ok(Map.of("code", 0, "message", "duplicate"));
        }

        // 3. 分发 action
        String action = str(body.get("action"));
        String handled;
        if ("workflow_action".equals(action)) {
            handled = handleWorkflowAction(body, tenantId);
        } else {
            // open_url 等前端行为,服务端仅确认
            handled = "ack";
        }

        markProcessed(eventId, tenantId);
        return ResponseEntity.ok(Map.of("code", 0, "message", handled));
    }

    /** 处理工作流动作:同意/拒绝对应任务。 */
    private String handleWorkflowAction(Map<String, Object> body, String tenantId) {
        String taskIdStr = str(body.get("workflowTaskId"));
        String result = str(body.get("workflowResult"));
        if (taskIdStr.isBlank()) {
            return "missing_task";
        }
        UUID taskId;
        try {
            taskId = UUID.fromString(taskIdStr);
        } catch (IllegalArgumentException e) {
            return "invalid_task_id";
        }

        Optional<WorkflowTaskEntity> opt = taskRepository.findById(taskId);
        if (opt.isEmpty()) {
            log.warn("[DingTalkAiCard] 工作流任务不存在 taskId={}", taskId);
            return "task_not_found";
        }
        WorkflowTaskEntity task = opt.get();
        // 租户隔离:非本租户任务拒绝
        if (tenantId != null && task.getTenantId() != null && !tenantId.equals(task.getTenantId())) {
            log.warn("[DingTalkAiCard] 跨租户任务操作被拒绝 taskId={}", taskId);
            return "forbidden";
        }

        boolean agree = !"reject".equalsIgnoreCase(result);
        task.setStatus(agree ? WorkflowTaskEntity.Status.APPROVED : WorkflowTaskEntity.Status.REJECTED);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        auditService.log(tenantId, "system", "system",
                "dingtalk.ai-card.workflow-action", "workflow_task",
                taskId.toString(), Map.of("action", agree ? "APPROVE" : "REJECT"));
        log.info("[DingTalkAiCard] 工作流任务已{} taskId={}", agree ? "同意" : "拒绝", taskId);
        return agree ? "approved" : "rejected";
    }

    private void markProcessed(String eventId, String tenantId) {
        try {
            externalMessageLogRepository.save(
                    new ExternalMessageLogEntity("dingtalk_aicard", eventId, tenantId));
        } catch (Exception e) {
            log.warn("[DingTalkAiCard] 记录事件标记失败(并发重复): id={}, err={}",
                    eventId, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
