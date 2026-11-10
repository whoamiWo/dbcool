package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.approval.ApprovalExternalBindingRepository;
import com.nocobase.approval.ApprovalRequestEntity;
import com.nocobase.approval.ApprovalRequestRepository;
import com.nocobase.approval.ApprovalTemplateEntity;
import com.nocobase.approval.ApprovalTemplateRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class DingTalkExternalApprovalServiceTest {
    private final ApprovalRequestRepository requests = mock(ApprovalRequestRepository.class);
    private final ApprovalTemplateRepository templates = mock(ApprovalTemplateRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ApprovalExternalBindingRepository bindings = mock(ApprovalExternalBindingRepository.class);
    private final DingTalkApprovalService http = mock(DingTalkApprovalService.class);
    private final DingTalkExternalConfig config = mock(DingTalkExternalConfig.class);
    private final UUID requestId = UUID.randomUUID();
    private ApprovalRequestEntity request;
    private DingTalkExternalApprovalService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        var projection = mock(com.nocobase.approval.ApprovalSearchProjection.class);
        service = new DingTalkExternalApprovalService(templates, requests,
                mock(com.nocobase.approval.ApprovalCcRepository.class),
                mock(com.nocobase.workflow.WorkflowRepository.class),
                mock(com.nocobase.workflow.WorkflowInstanceRepository.class), users, bindings, http, config,
                projection, new ObjectMapper(), tx, true);
        request = new ApprovalRequestEntity();
        request.setId(requestId);
        request.setTenantId("tenant_a");
        request.setTemplateId(UUID.randomUUID());
        request.setApplicantId(UUID.randomUUID());
        request.setStatus("PENDING");
        request.setExternalPushStatus("PENDING");
        request.setCorpId("corp-a");
        request.setExternalProcessCode("PROC-1");
        when(requests.claimPush(requestId)).thenAnswer(inv -> {
            request.setExternalPushStatus("PUSHING");
            return 1;
        });
        request.setFormDataJson("{\"days\":3}");
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(requests.findByIdAndTenantId(requestId, "tenant_a")).thenReturn(Optional.of(request));
        when(requests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApprovalTemplateEntity template = new ApprovalTemplateEntity();
        template.setExternalProcessCode("PROC-1");
        template.setExternalConfig("{}");
        when(templates.findByIdAndTenantId(request.getTemplateId(), "tenant_a"))
                .thenReturn(Optional.of(template));
        UserEntity applicant = new UserEntity();
        applicant.setExternalSource("DINGTALK");
        applicant.setExternalId("ding-user-1");
        when(users.findByIdAndTenantId(request.getApplicantId(), "tenant_a"))
                .thenReturn(Optional.of(applicant));
        when(config.resolve(anyString(), anyString(), any(), anyString())).thenReturn(
                new DingTalkExternalConfig.Resolved("corp-a", "PROC-1", "ding-user-1", -1L,
                        List.of(), List.of(Map.of("name", "天数", "value", "3"))));
    }

    @Test
    void successfulHttpCommitsExactBindingAndPushStatus() {
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn("oa-instance-1");
        ApprovalRequestEntity result = service.push(requestId);
        assertThat(result.getExternalPushStatus()).isEqualTo("PUSHED");
        assertThat(result.getExternalInstanceId()).isEqualTo("oa-instance-1");
        verify(bindings).saveAndFlush(any());
    }

    @Test
    void httpFailureKeepsRequestPendingAndNoExternalId() {
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OA unavailable"));
        ApprovalRequestEntity result = service.push(requestId);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getExternalPushStatus()).isEqualTo("FAILED");
        assertThat(result.getExternalInstanceId()).isNull();
        verify(bindings, never()).saveAndFlush(any());
    }

    @Test
    void disabledApplicantNeverCreatesExternalApproval() {
        UserEntity disabled = users.findByIdAndTenantId(request.getApplicantId(), "tenant_a").orElseThrow();
        disabled.setEnabled(false);
        assertThat(service.push(requestId).getExternalPushError()).contains("禁用或离职");
        assertThat(request.getExternalPushStatus()).isEqualTo("FAILED");
        verifyNoExternalHttp();
    }

    @Test
    void missingDingTalkIdentityNeverCreatesExternalApproval() {
        UserEntity local = users.findByIdAndTenantId(request.getApplicantId(), "tenant_a").orElseThrow();
        local.setExternalSource(null);
        assertThat(service.push(requestId).getExternalPushError()).contains("外部身份");
        assertThat(request.getExternalPushStatus()).isEqualTo("FAILED");
        verifyNoExternalHttp();
    }

    private void verifyNoExternalHttp() {
        verify(http, never()).createApproval(anyString(), anyString(), anyString(), anyString(),
                any(), anyList(), anyList());
    }

    @Test
    void ordinaryFailureRetryActuallyRetriesHttp() {
        request.setExternalPushStatus("FAILED");
        request.setExternalPushError("UPSTREAM_HTTP: failed");
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn("oa-retry");
        assertThat(service.retry("tenant_a", requestId).getExternalPushStatus()).isEqualTo("PUSHED");
        verify(http).createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    void lostPushClaimNeverSendsHttp() {
        when(requests.claimPush(requestId)).thenReturn(0);
        assertThat(service.push(requestId).getExternalPushStatus()).isEqualTo("FAILED");
        verifyNoExternalHttp();
    }

    @Test
    void upstreamErrorNeverStoresRawCredentials() {
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "https://oapi.dingtalk.com/gettoken?appkey=raw-key&appsecret=raw-secret"));
        var result = service.push(requestId);
        assertThat(result.getExternalPushError()).isEqualTo("UPSTREAM_HTTP: 钉钉接口请求失败，请稍后重试")
                .doesNotContain("raw-key", "raw-secret");
    }

    @Test
    void missingExternalIdentityIsNotInfinitelyRetried() {
        request.setExternalPushStatus("FAILED");
        request.setExternalPushError("申请人未绑定钉钉外部身份");
        users.findByIdAndTenantId(request.getApplicantId(), "tenant_a").orElseThrow().setExternalSource(null);
        assertThatThrownBy(() -> service.retry("tenant_a", requestId))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("外部身份");
        verifyNoExternalHttp();
    }

    @Test
    void unknownBindingWithTrustedRequestTripleCanBeReconciled() {
        request.setExternalPushStatus("FAILED");
        request.setExternalPushError("UNKNOWN_BINDING: pending");
        request.setExternalInstanceId("oa-unknown");
        assertThat(service.retry("tenant_a", requestId).getExternalPushStatus()).isEqualTo("PUSHED");
        verifyNoExternalHttp();
        verify(bindings).saveAndFlush(any());
    }

    @Test
    void bindingFailurePreservesExternalIdAndRetryCreatesExactBindingWithoutHttp() {
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn("oa-preserved");
        when(bindings.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("database failure"))
                .thenAnswer(inv -> inv.getArgument(0));
        ApprovalRequestEntity failed = service.push(requestId);
        assertThat(failed.getExternalInstanceId()).isEqualTo("oa-preserved");
        assertThat(failed.getCorpId()).isEqualTo("corp-a");
        assertThat(failed.getExternalProcessCode()).isEqualTo("PROC-1");
        when(bindings.findByCorpIdAndProcessCodeAndProcessInstanceId("corp-a", "PROC-1", "oa-preserved"))
                .thenReturn(Optional.empty());
        ApprovalRequestEntity repaired = service.retry("tenant_a", requestId);
        assertThat(repaired.getExternalPushStatus()).isEqualTo("PUSHED");
        verify(bindings, org.mockito.Mockito.times(2)).saveAndFlush(any());
        verify(http, org.mockito.Mockito.times(1)).createApproval(anyString(), anyString(), anyString(),
                anyString(), any(), anyList(), anyList());
    }

    @Test
    void bindingFailureWithExactExistingBindingRepairsWithoutRecreating() {
        when(http.createApproval(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn("oa-already-created");
        when(bindings.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        ApprovalRequestEntity result = service.push(requestId);
        assertThat(result.getExternalPushError()).startsWith("UNKNOWN_BINDING");
        var binding = new com.nocobase.approval.ApprovalExternalBindingEntity();
        binding.setTenantId("tenant_a");
        binding.setRequestId(requestId);
        binding.setCorpId("corp-a");
        binding.setProcessCode("PROC-1");
        binding.setProcessInstanceId("oa-already-created");
        when(bindings.findByTenantIdAndRequestId("tenant_a", requestId)).thenReturn(Optional.of(binding));
        when(bindings.findByCorpIdAndProcessCodeAndProcessInstanceId("corp-a", "PROC-1", "oa-already-created"))
                .thenReturn(Optional.of(binding));
        ApprovalRequestEntity repaired = service.retry("tenant_a", requestId);
        assertThat(repaired.getExternalPushStatus()).isEqualTo("PUSHED");
        assertThat(repaired.getExternalInstanceId()).isEqualTo("oa-already-created");
        verify(http, org.mockito.Mockito.times(1)).createApproval(
                anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyList());
    }
}
