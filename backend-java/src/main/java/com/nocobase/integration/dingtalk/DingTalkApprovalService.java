package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 钉钉 OA 审批服务 — 创建/查询审批实例。
 *
 * <p><b>Week 3 任务</b>: 实现平台发起钉钉审批 → 回调更新工作流状态。
 * <ul>
 *   <li>创建审批实例: {@link #createApproval(String, String, Map)}</li>
 *   <li>查询审批结果: {@link #getApprovalStatus(String)}</li>
 *   <li>处理回调: {@link DingTalkAdapter#onApprovalCallback(Map, String)}</li>
 * </ul>
 */
@Service
public class DingTalkApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkApprovalService.class);

    private final DingTalkAppService appService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final com.nocobase.workflow.WorkflowInstanceRepository workflowInstanceRepository;
    private final com.nocobase.workflow.WorkflowTaskRepository workflowTaskRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public DingTalkApprovalService(DingTalkAppService appService,
com.nocobase.workflow.WorkflowInstanceRepository workflowInstanceRepository,
com.nocobase.workflow.WorkflowTaskRepository workflowTaskRepository,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {


        this.appService = appService;
            this.workflowInstanceRepository = workflowInstanceRepository;
        this.workflowTaskRepository = workflowTaskRepository;
        this.eventPublisher = eventPublisher;
}

    /** 是否已配置钉钉应用凭证。 */
    private boolean isConfigured() {
        return appService.isConfigured();
    }

    /**
     * 创建钉钉 OA 审批实例。
     *
     * @param processCode  审批模板编码
     * @param title        审批标题
     * @param formValues   表单数据
     * @return 审批实例 ID
     */
    public String createApproval(String processCode, String title, Map<String, Object> formValues) {
        if (!isConfigured()) {
            log.warn("[DingTalk] 审批创建失败: 应用未配置");
            return null;
        }

        try {
            String url = "https://oapi.dingtalk.com/topapi/process/instance/create";
            String body = objectMapper.writeValueAsString(Map.of(
                    "process_code", processCode,
                    "title", title,
                    "form_values", formValues
            ));

            String response = new org.springframework.web.client.RestTemplate()
                    .postForObject(url, body, String.class);

            JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
            String instanceId = json.path("result").path("instance_id").asText(null);

            if (instanceId != null) {
                log.info("[DingTalk] 创建审批实例成功: {}", instanceId);
            } else {
                log.warn("[DingTalk] 创建审批实例失败: {}", json.toString());
            }

            return instanceId;

        } catch (Exception e) {
            log.error("[DingTalk] 创建审批实例失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查询审批实例状态。
     *
     * @param instanceId 审批实例 ID
     * @return 审批状态 (agree/reject/pending)
     */
    public String getApprovalStatus(String instanceId) {
        if (!isConfigured() || instanceId == null) {
            return null;
        }

        try {
            String url = "https://oapi.dingtalk.com/topapi/process/instance/get?instance_id=" + instanceId;
            String response = new org.springframework.web.client.RestTemplate()
                    .getForObject(url, String.class);

            JsonNode json = objectMapper.readTree(response == null ? "{}" : response);
            return json.path("result").path("status").asText(null);

        } catch (Exception e) {
            log.warn("[DingTalk] 查询审批状态失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 取消审批实例。
     *
     * @param instanceId 审批实例 ID
     */
    public void cancelApproval(String instanceId) {
        if (!isConfigured() || instanceId == null) {
            return;
        }

        try {
            String url = "https://oapi.dingtalk.com/topapi/process/instance/cancel";
            String body = objectMapper.writeValueAsString(Map.of("instance_id", instanceId));

            new org.springframework.web.client.RestTemplate()
                    .postForObject(url, body, String.class);

            log.info("[DingTalk] 取消审批实例: {}", instanceId);

        } catch (Exception e) {
            log.warn("[DingTalk] 取消审批实例失败: {}", e.getMessage());
        }
    }

    /**
     * 处理钉钉审批回调，更新工作流节点状态。
     *
     * @param instanceId 审批实例 ID
     * @param result     审批结果 (agree/reject)
     */
    /**
     * Wave4 待办池 #10：处理钉钉审批回调 —— 业务闭环。
     *
     * <p>发起时 DingTalkApprovalNodeHandler 已把 dingtalkInstanceId 绑定写入工作流
     * 实例的 trigger_data_json；此处按绑定反查实例，把该实例上全部 PENDING 任务按
     * agree/reject 标记：agree 且无剩余任务 → 实例 COMPLETED；reject → 实例 FAILED
     * （与手动 reject 语义一致）。异常吞掉不影响回调 ACK（钉钉会重试）。
     *
     * @return 是否找到绑定并处理（未绑定时 false，便于日志排查）
     */
    public boolean handleApprovalCallback(String instanceId, String result) {
        log.info("[DingTalk] 处理审批回调: instance={}, result={}", instanceId, result);
        try {
            // Wave4 待办池 #10：发布回调事件（解耦 —— 直接注入 WorkflowTaskService 会形成
            // ApprovalService→TaskService→Engine→ApprovalNodeHandler→ApprovalService 的构造循环依赖，
            // Spring 无法启动，实测）。WorkflowTaskService 的 @EventListener 完成推进。
            boolean agree = "agree".equalsIgnoreCase(result);
            eventPublisher.publishEvent(
                    new com.nocobase.workflow.DingTalkApprovalCallbackEvent(
                            instanceId, agree, "钉钉审批回调: " + result));
            return true;
        } catch (Exception e) {
            log.error("[DingTalk] 审批回调事件发布失败: {}", e.getMessage(), e);
            return false;
        }
    }

}
