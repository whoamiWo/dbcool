package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.approval.ApprovalCcEntity;
import com.nocobase.approval.ApprovalCcRepository;
import com.nocobase.approval.ApprovalExternalBindingEntity;
import com.nocobase.approval.ApprovalExternalBindingRepository;
import com.nocobase.approval.ApprovalRequestEntity;
import com.nocobase.approval.ApprovalRequestRepository;
import com.nocobase.approval.ApprovalTemplateEntity;
import com.nocobase.approval.ApprovalTemplateRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** OA HTTP 与本地短事务的唯一协调器；禁止在任何 @Transactional 方法中执行 HTTP。 */
@Service
public class DingTalkExternalApprovalService {
    private static final Logger log = LoggerFactory.getLogger(DingTalkExternalApprovalService.class);
    private final ApprovalTemplateRepository templateRepository;
    private final ApprovalRequestRepository requestRepository;
    private final ApprovalCcRepository ccRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final UserRepository userRepository;
    private final ApprovalExternalBindingRepository bindingRepository;
    private final DingTalkApprovalService approvalService;
    private final DingTalkExternalConfig configParser;
    private final com.nocobase.approval.ApprovalSearchProjection approvalProjection;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final boolean pushEnabled;

    public DingTalkExternalApprovalService(ApprovalTemplateRepository templateRepository,
            ApprovalRequestRepository requestRepository, ApprovalCcRepository ccRepository,
            WorkflowRepository workflowRepository, WorkflowInstanceRepository instanceRepository,
            UserRepository userRepository, ApprovalExternalBindingRepository bindingRepository,
            DingTalkApprovalService approvalService, DingTalkExternalConfig configParser,
            com.nocobase.approval.ApprovalSearchProjection approvalProjection,
            ObjectMapper mapper, PlatformTransactionManager transactionManager,
            @Value("${approval.dingtalk.push.enabled:false}") boolean pushEnabled) {
        this.templateRepository = templateRepository;
        this.requestRepository = requestRepository;
        this.ccRepository = ccRepository;
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.userRepository = userRepository;
        this.bindingRepository = bindingRepository;
        this.approvalService = approvalService;
        this.approvalProjection = approvalProjection;
        this.configParser = configParser;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.pushEnabled = pushEnabled;
    }

    /** 第一节点必须是外部节点，确保外推失败时没有本地人工审批可绕过 OA。 */
    public UUID createLocal(String tenantId, UUID applicantId, UUID templateId,
                            Map<String, Object> formData, List<UUID> ccUserIds) {
        if (!pushEnabled) throw bad("钉钉外推未启用");
        return transactions.execute(status -> {
            ApprovalTemplateEntity template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                    .orElseThrow(() -> notFound("审批模板不存在"));
            if (!template.isEnabled()) throw bad("审批模板已停用");
            WorkflowEntity workflow = workflowRepository.findByIdAndTenantId(template.getWorkflowId(), tenantId)
                    .orElseThrow(() -> bad("审批模板未绑定有效工作流"));
            List<Map<String, Object>> nodes = nodes(workflow.getNodesJson());
            if (nodes.isEmpty() || !"DINGTALK_APPROVAL".equals(nodes.get(0).get("type"))) {
                throw bad("外推模板的第一节点必须是 DINGTALK_APPROVAL，且不能有本地前置审批");
            }
            DingTalkExternalConfig.Resolved snapshot = configParser.resolve(
                    template.getExternalProcessCode(), template.getExternalConfig(),
                    formData == null ? Map.of() : formData, null);
            UserEntity applicant = userRepository.findByIdAndTenantId(applicantId, tenantId)
                    .orElseThrow(() -> notFound("申请人不存在"));
            if (!applicant.isEnabled() || "RESIGNED".equals(applicant.getStatus())
                    || !"DINGTALK".equals(applicant.getExternalSource())
                    || applicant.getExternalId() == null || applicant.getExternalId().isBlank()) {
                throw bad("申请人必须处于启用状态且已绑定钉钉外部身份");
            }
            ApprovalRequestEntity request = new ApprovalRequestEntity();
            request.setId(UUID.randomUUID());
            request.setTenantId(tenantId);
            request.setTemplateId(templateId);
            request.setApplicantId(applicantId);
            request.setFormDataJson(json(formData == null ? Map.of() : formData));
            request.setStatus(ApprovalRequestEntity.STATUS_PENDING);
            request.setExternalPushStatus("PENDING");
            request.setExternalProcessCode(snapshot.processCode());
            request.setCorpId(snapshot.corpId());
            request.setSubmittedAt(Instant.now());
            requestRepository.save(request);
            if (ccUserIds != null) {
                new LinkedHashSet<>(ccUserIds).forEach(uid ->
                        ccRepository.save(new ApprovalCcEntity(request.getId(), uid)));
            }
            WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
            instance.setId(UUID.randomUUID());
            instance.setWorkflowId(workflow.getId());
            instance.setTenantId(tenantId);
            instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
            instance.setCurrentNodeIndex(0);
            instance.setStartedAt(Instant.now());
            instance.setTriggerDataJson(json(Map.of("requestId", request.getId().toString(),
                    "applicantId", applicant.getId().toString(), "formData", formData == null ? Map.of() : formData)));
            instanceRepository.save(instance);
            request.setInstanceId(instance.getId());
            requestRepository.save(request);
            approvalProjection.index(tenantId, request.getId());
            return request.getId();
        });
    }

    /** 本方法刻意不加 @Transactional；每次 HTTP 前后使用独立 REQUIRES_NEW 短事务。 */
    public ApprovalRequestEntity push(UUID requestId) {
        PushData data;
        try {
            data = transactions.execute(status -> {
                if (requestRepository.claimPush(requestId) != 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "单据正在外推或已处理");
                }
                return loadPushData(requestId);
            });
        } catch (Exception e) {
            return transactions.execute(status -> failPush(requestId, safeError(e), null, null, false));
        }
        try {
            DingTalkExternalConfig.Resolved resolved = configParser.resolve(
                    data.template().getExternalProcessCode(), data.template().getExternalConfig(),
                    data.formData(), data.originatorUserId());
            String externalId = approvalService.createApproval(data.request().getTenantId(),
                    resolved.corpId(), resolved.processCode(), resolved.originatorUserId(),
                    resolved.deptId(), resolved.approvers(), resolved.formComponentValues());
            try {
                return transactions.execute(status -> completePush(requestId, resolved.corpId(),
                        resolved.processCode(), externalId));
            } catch (Exception bindingFailure) {
                log.error("[approval] OA 已创建但本地绑定失败 request={} externalId={} category={}",
                        requestId, externalId, bindingFailure.getClass().getSimpleName());
                return transactions.execute(status -> failPush(requestId,
                        "UNKNOWN_BINDING: OA 实例可能已创建，请先按 processCode/实例 ID 对账",
                        resolved.corpId(), externalId, true));
            }
        } catch (Exception e) {
            String error = safeError(e);
            log.warn("[approval] OA 外推失败 request={} error={}", requestId, error);
            return transactions.execute(status -> failPush(requestId, error, null, null, false));
        }
    }

    public ApprovalRequestEntity retry(String tenantId, UUID requestId) {
        ApprovalRequestEntity request = transactions.execute(status -> {
            ApprovalRequestEntity r = requestRepository.findByIdAndTenantId(requestId, tenantId)
                    .orElseThrow(() -> notFound("审批单据不存在"));
            if (!ApprovalRequestEntity.STATUS_PENDING.equals(r.getStatus())) throw bad("已终结单据不能重试外推");
            ApprovalTemplateEntity template = templateRepository.findByIdAndTenantId(r.getTemplateId(), tenantId)
                    .orElseThrow(() -> bad("审批模板不存在"));
            if (!template.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "审批模板已停用");
            UserEntity applicant = userRepository.findByIdAndTenantId(r.getApplicantId(), tenantId)
                    .orElseThrow(() -> notFound("申请人不存在"));
            requireActiveDingTalkApplicant(applicant);
            if (!"FAILED".equals(r.getExternalPushStatus())) throw bad("仅外推失败的单据可以重试");
            if (r.getExternalPushError() != null && r.getExternalPushError().startsWith("UNKNOWN_BINDING")) {
                if (r.getExternalInstanceId() == null || r.getExternalInstanceId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "OA 实例可能已创建但绑定失败，请先对账，不允许盲目重建");
                }
                // 只使用 request 上已持久化的 OA 三元组补绑，绝不再次创建。
                if (r.getCorpId() == null || r.getCorpId().isBlank()
                        || r.getExternalProcessCode() == null || r.getExternalProcessCode().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少 corpId/processCode，请先对账绑定");
                }
                var exact = bindingRepository.findByCorpIdAndProcessCodeAndProcessInstanceId(
                        r.getCorpId(), r.getExternalProcessCode(), r.getExternalInstanceId());
                if (exact.isPresent()) {
                    var binding = exact.get();
                    if (!tenantId.equals(binding.getTenantId()) || !requestId.equals(binding.getRequestId())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "OA 实例已绑定其它单据，拒绝错误绑定");
                    }
                } else {
                    if (bindingRepository.findByTenantIdAndRequestId(tenantId, requestId).isPresent()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "单据已有不一致的外部绑定");
                    }
                    ApprovalExternalBindingEntity binding = new ApprovalExternalBindingEntity();
                    binding.setId(UUID.randomUUID());
                    binding.setTenantId(tenantId);
                    binding.setRequestId(requestId);
                    binding.setCorpId(r.getCorpId());
                    binding.setProcessCode(r.getExternalProcessCode());
                    binding.setProcessInstanceId(r.getExternalInstanceId());
                    binding.setCreatedAt(Instant.now());
                    bindingRepository.saveAndFlush(binding);
                }
                r.setExternalPushStatus("PUSHED");
                r.setExternalPushError(null);
                return requestRepository.save(r);
            }
            r.setExternalPushStatus("PENDING");
            r.setExternalPushError(null);
            return requestRepository.save(r);
        });
        if (request.getExternalPushStatus() == null || !"PENDING".equals(request.getExternalPushStatus())) {
            return request;
        }
        return push(request.getId());
    }

    private PushData loadPushData(UUID requestId) {
        ApprovalRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> notFound("审批单据不存在"));
        if (!"PUSHING".equals(request.getExternalPushStatus())) throw bad("单据不在待外推状态");
        ApprovalTemplateEntity template = templateRepository.findByIdAndTenantId(
                request.getTemplateId(), request.getTenantId()).orElseThrow(() -> bad("模板不存在"));
        if (request.getExternalProcessCode() == null
                || !request.getExternalProcessCode().equals(template.getExternalProcessCode())) {
            throw bad("模板已变更，请管理员重新配置外推映射");
        }
        if (!ApprovalRequestEntity.STATUS_PENDING.equals(request.getStatus())) throw bad("已终结单据不能外推");
        if (!template.isEnabled()) throw bad("审批模板已停用");
        UserEntity applicant = userRepository.findByIdAndTenantId(request.getApplicantId(), request.getTenantId())
                .orElseThrow(() -> bad("申请人不存在"));
        requireActiveDingTalkApplicant(applicant);
        return new PushData(request, template, formData(request.getFormDataJson()), applicant.getExternalId());
    }

    private ApprovalRequestEntity completePush(UUID requestId, String corpId,
                                                String processCode, String externalId) {
        ApprovalRequestEntity request = requestRepository.findById(requestId).orElseThrow();
        if (!ApprovalRequestEntity.STATUS_PENDING.equals(request.getStatus())) throw bad("已终结单据不能绑定");
        ApprovalExternalBindingEntity binding = new ApprovalExternalBindingEntity();
        binding.setId(UUID.randomUUID());
        binding.setTenantId(request.getTenantId());
        binding.setRequestId(requestId);
        binding.setCorpId(corpId);
        binding.setProcessCode(processCode);
        binding.setProcessInstanceId(externalId);
        binding.setCreatedAt(Instant.now());
        bindingRepository.saveAndFlush(binding);
        request.setExternalInstanceId(externalId);
        request.setCorpId(corpId);
        request.setExternalProcessCode(processCode);
        request.setExternalPushStatus("PUSHED");
        request.setExternalPushError(null);
        return requestRepository.save(request);
    }

    private ApprovalRequestEntity failPush(UUID requestId, String error,
                                            String corpId, String externalId,
                                            boolean preserveUnknownBinding) {
        ApprovalRequestEntity request = requestRepository.findById(requestId).orElseThrow();
        request.setExternalPushStatus("FAILED");
        request.setExternalPushError(error);
        if (corpId != null) request.setCorpId(corpId);
        if (externalId != null) request.setExternalInstanceId(externalId);
        return requestRepository.save(request);
    }

    private static void requireActiveDingTalkApplicant(UserEntity applicant) {
        if (!applicant.isEnabled() || "RESIGNED".equals(applicant.getStatus())
                || "DINGTALK".equals(applicant.getDisableReason())) {
            throw bad("申请人已禁用或离职，禁止外推");
        }
        if (!"DINGTALK".equals(applicant.getExternalSource()) || applicant.getExternalId() == null
                || applicant.getExternalId().isBlank()) {
            throw bad("申请人未绑定钉钉外部身份");
        }
    }

    private static String safeError(Exception e) {
        if (e instanceof org.springframework.web.client.RestClientException) {
            return "UPSTREAM_HTTP: 钉钉接口请求失败，请稍后重试";
        }
        if (e instanceof org.springframework.dao.DataAccessException) {
            return "DATABASE: 本地持久化失败，请管理员检查";
        }
        if (e instanceof ResponseStatusException rse) {
            return String.valueOf(rse.getReason());
        }
        return e.getClass().getSimpleName();
    }

    private Map<String, Object> formData(String value) {
        try { return mapper.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw bad("单据表单不是有效 JSON"); }
    }

    private List<Map<String, Object>> nodes(String value) {
        try { return mapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw bad("工作流节点不是有效 JSON"); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw bad("数据无法序列化"); }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record PushData(ApprovalRequestEntity request, ApprovalTemplateEntity template,
                            Map<String, Object> formData, String originatorUserId) {}
}
