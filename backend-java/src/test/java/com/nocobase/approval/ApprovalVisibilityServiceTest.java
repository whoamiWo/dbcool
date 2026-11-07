package com.nocobase.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.RoleRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** R1e：审批详情可见性（与搜索结果共用谓词）。 */
class ApprovalVisibilityServiceTest {

    private ApprovalCcRepository ccRepository;
    private WorkflowTaskRepository taskRepository;
    private RoleRepository roleRepository;
    private ApprovalVisibilityService service;

    private static final String TENANT = "tenant_a";
    private final UUID applicant = UUID.randomUUID();
    private final UUID ccUser = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ccRepository = mock(ApprovalCcRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        roleRepository = mock(RoleRepository.class);
        service = new ApprovalVisibilityService(ccRepository, taskRepository, roleRepository);
        when(ccRepository.findByRequestId(any())).thenReturn(List.of());
        when(taskRepository.existsByInstanceIdAndAssignee(any(), any())).thenReturn(false);
        when(roleRepository.findRoleNamesByUserId(any(), any())).thenReturn(List.of("USER"));
    }

    private ApprovalRequestEntity request() {
        ApprovalRequestEntity r = new ApprovalRequestEntity();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT);
        r.setApplicantId(applicant);
        r.setInstanceId(instanceId);
        return r;
    }

    @Test
    @DisplayName("申请人可见")
    void applicant_canView() {
        assertThat(service.canView(TENANT, request(), applicant)).isTrue();
    }

    @Test
    @DisplayName("抄送人可见")
    void cc_canView() {
        ApprovalRequestEntity r = request();
        ApprovalCcEntity cc = new ApprovalCcEntity(r.getId(), ccUser);
        when(ccRepository.findByRequestId(r.getId())).thenReturn(List.of(cc));
        assertThat(service.canView(TENANT, r, ccUser)).isTrue();
    }

    @Test
    @DisplayName("任务处理人（历史+当前）可见")
    void assignee_canView() {
        ApprovalRequestEntity r = request();
        when(taskRepository.existsByInstanceIdAndAssignee(instanceId, approver)).thenReturn(true);
        assertThat(service.canView(TENANT, r, approver)).isTrue();
    }

    @Test
    @DisplayName("ADMIN 可见")
    void admin_canView() {
        ApprovalRequestEntity r = request();
        when(roleRepository.findRoleNamesByUserId(admin, TENANT)).thenReturn(List.of("admin"));
        assertThat(service.canView(TENANT, r, admin)).isTrue();
    }

    @Test
    @DisplayName("无关用户不可见 → 404（不泄露单据是否存在）")
    void outsider_gets404() {
        ApprovalRequestEntity r = request();
        assertThat(service.canView(TENANT, r, outsider)).isFalse();
        assertThatThrownBy(() -> service.assertCanView(TENANT, r, outsider))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    @DisplayName("userId 为 null → fail-closed")
    void nullUser_denied() {
        assertThat(service.canView(TENANT, request(), null)).isFalse();
    }
}
