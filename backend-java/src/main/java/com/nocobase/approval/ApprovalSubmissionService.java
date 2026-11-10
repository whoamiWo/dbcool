package com.nocobase.approval;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本地提交的独立事务代理；避免 ApprovalService 同类自调用绕过事务。 */
@Service
public class ApprovalSubmissionService {
    private final ApprovalService approvalService;

    public ApprovalSubmissionService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Map<String, Object> submit(String tenantId, UUID applicantId, UUID templateId,
                                      Map<String, Object> formData, List<UUID> ccUserIds) {
        return approvalService.submitLocal(tenantId, applicantId, templateId, formData, ccUserIds);
    }
}
