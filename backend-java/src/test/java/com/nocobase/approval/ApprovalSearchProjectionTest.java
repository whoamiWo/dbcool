package com.nocobase.approval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.search.UnifiedSearchService;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalSearchProjectionTest {
    @Test
    void indexesOnlyApprovalTypeWithinSameTenant() {
        var requests = mock(ApprovalRequestRepository.class);
        var search = mock(UnifiedSearchService.class);
        var projection = new ApprovalSearchProjection(requests, search, mock(WorkflowTaskRepository.class));
        var request = new ApprovalRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTenantId("tenant_a");
        request.setTemplateId(UUID.randomUUID());
        request.setApplicantId(UUID.randomUUID());
        request.setFormDataJson("{}");
        request.setStatus("PENDING");
        when(requests.findByIdAndTenantId(request.getId(), "tenant_a")).thenReturn(Optional.of(request));
        projection.index("tenant_a", request.getId());
        verify(search).indexEntity(eq("approval"), eq(request.getId().toString()), eq("tenant_a"),
                eq("审批申请"), eq("{}"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wrongTenantCannotWriteProjection() {
        var requests = mock(ApprovalRequestRepository.class);
        var projection = new ApprovalSearchProjection(requests, mock(UnifiedSearchService.class),
                mock(WorkflowTaskRepository.class));
        assertThatThrownBy(() -> projection.index("tenant_b", UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
