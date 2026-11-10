package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class DingTalkApprovalServiceTest {
    private DingTalkAppService appService;
    private RestTemplate restTemplate;
    private DingTalkApprovalService service;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        restTemplate = mock(RestTemplate.class);
        service = new DingTalkApprovalService(appService, restTemplate, new ObjectMapper());
        when(appService.isTenantConfigured("tenant_a")).thenReturn(true);
        when(appService.getAppAccessToken("tenant_a")).thenReturn("tenant-token");
    }

    @Test
    void createApproval_usesOfficialContractAndTenantToken() {
        when(restTemplate.postForEntity(eq("https://api.dingtalk.com/v1.0/workflow/processInstances"),
                any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"instanceId\":\"oa-instance-1\"}"));
        String id = service.createApproval("tenant_a", "corp-a", "PROC-1", "ding-user-1", -1L,
                List.of(), List.of(Map.of("name", "天数", "value", "3")));
        assertThat(id).isEqualTo("oa-instance-1");
        @SuppressWarnings("rawtypes") ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("https://api.dingtalk.com/v1.0/workflow/processInstances"),
                captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("x-acs-dingtalk-access-token"))
                .isEqualTo("tenant-token");
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body).containsEntry("processCode", "PROC-1")
                .containsEntry("originatorUserId", "ding-user-1").containsEntry("deptId", -1L);
        assertThat(body).doesNotContainKeys("process_code", "form_values", "title");
    }

    @Test
    void createApproval_noTenantCredentials_doesNotSendHttp() {
        when(appService.isTenantConfigured("tenant_a")).thenReturn(false);
        assertThatThrownBy(() -> service.createApproval("tenant_a", "corp", "proc", "user", -1L,
                List.of(), List.of())).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void createApproval_missingInstanceId_fails() {
        when(restTemplate.postForEntity(eq("https://api.dingtalk.com/v1.0/workflow/processInstances"),
                any(), eq(String.class))).thenReturn(ResponseEntity.ok("{}"));
        assertThatThrownBy(() -> service.createApproval("tenant_a", "corp", "proc", "user", -1L,
                List.of(), List.of())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("审批实例 ID");
    }
}
