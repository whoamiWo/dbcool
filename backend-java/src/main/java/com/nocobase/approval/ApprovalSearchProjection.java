package com.nocobase.approval;

import com.nocobase.search.UnifiedSearchService;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 仅按租户重新加载审批后写搜索索引；不接受调用方裸实体跨租户投影。 */
@Service
public class ApprovalSearchProjection {
    private static final Logger log = LoggerFactory.getLogger(ApprovalSearchProjection.class);
    private final ApprovalRequestRepository requestRepository;
    private final UnifiedSearchService searchIndexService;
    private final WorkflowTaskRepository taskRepository;

    public ApprovalSearchProjection(ApprovalRequestRepository requestRepository,
                                    @org.springframework.lang.Nullable UnifiedSearchService searchIndexService,
                                    WorkflowTaskRepository taskRepository) {
        this.requestRepository = requestRepository;
        this.searchIndexService = searchIndexService;
        this.taskRepository = taskRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void index(String tenantId, UUID requestId) {
        ApprovalRequestEntity request = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批单据不存在"));
        if (searchIndexService == null) return;
        try {
            List<WorkflowTaskEntity> tasks = request.getInstanceId() == null
                    ? List.of() : taskRepository.findByInstanceId(request.getInstanceId());
            String taskNodeName = tasks.stream()
                    .filter(t -> t.getStatus() == WorkflowTaskEntity.Status.PENDING)
                    .map(WorkflowTaskEntity::getTitle).findFirst().orElse(null);
            Map<String, Object> record = new HashMap<>();
            record.put("title", "审批申请");
            record.put("status", request.getStatus());
            record.put("taskNodeName", taskNodeName);
            record.put("currentTaskId", request.getCurrentTaskId() == null
                    ? null : request.getCurrentTaskId().toString());
            record.put("applicantId", request.getApplicantId().toString());
            record.put("templateId", request.getTemplateId().toString());
            record.put("formData", request.getFormDataJson());
            record.put("createdAt", request.getSubmittedAt() == null
                    ? Instant.now().toString() : request.getSubmittedAt().toString());
            searchIndexService.indexEntity("approval", request.getId().toString(), tenantId,
                    "审批申请", request.getFormDataJson(), record);
        } catch (Exception e) {
            log.warn("[approval] 统一搜索索引写入失败 requestId={} category={}",
                    requestId, e.getClass().getSimpleName());
        }
    }
}
