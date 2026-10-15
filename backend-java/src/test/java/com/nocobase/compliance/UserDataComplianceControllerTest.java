package com.nocobase.compliance;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.userdata.UserDataErasureRequest;
import com.nocobase.userdata.UserDataExport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PHASE105: UserDataComplianceController 测试 (4 端点: admin export/erase, self export/erase)。 */
class UserDataComplianceControllerTest {

    private UserDataErasureService service;
    private AuditService auditService;
    private UserDataComplianceController controller;

    private static final String TENANT = "tenant_default";
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);

    @BeforeEach
    void setUp() {
        service = mock(UserDataErasureService.class);
        auditService = mock(AuditService.class);
        controller = new UserDataComplianceController(service, auditService);
    }

    @Test
    void exportUserDataForAdmin_returns200() {
        UserDataExport export = new UserDataExport();
        when(service.exportUserData(eq("target-user"), eq(TENANT), eq(USER.userId().toString())))
                .thenReturn(export);

        ResponseEntity<Map<String, Object>> resp =
                controller.exportUserDataForAdmin("target-user", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        assertThat(resp.getBody().get("data")).isSameAs(export);
        verify(auditService).log(eq(TENANT), eq(USER.userId().toString()), eq("admin"),
                eq("user.data.export"), anyString(), anyString(), any(Map.class));
    }

    @Test
    void eraseUserDataForAdmin_returns200() {
        UserDataErasureRequest result = new UserDataErasureRequest();
        result.setSuccess(true);
        when(service.erasesUserData(eq("target-user"), eq(USER.userId().toString()), eq(TENANT)))
                .thenReturn(result);

        ResponseEntity<Map<String, Object>> resp =
                controller.eraseUserDataForAdmin("target-user", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        assertThat(resp.getBody().get("data")).isSameAs(result);
    }

    @Test
    void exportMyData_returns200() {
        UserDataExport export = new UserDataExport();
        when(service.exportUserData(eq(USER.userId().toString()), eq(TENANT), eq(USER.userId().toString())))
                .thenReturn(export);

        ResponseEntity<Map<String, Object>> resp = controller.exportMyData(USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("data")).isSameAs(export);
        verify(auditService).log(eq(TENANT), eq(USER.userId().toString()), eq("admin"),
                eq("user.data.export"), anyString(), anyString(), any(Map.class));
    }

    @Test
    void requestErasureMyData_returns200WithCounters() {
        UserDataErasureRequest result = new UserDataErasureRequest();
        result.setSuccess(true);
        result.setMessagesErased(5);
        result.setConversationsErased(2);
        when(service.erasesUserData(eq(USER.userId().toString()), eq(USER.userId().toString()), eq(TENANT)))
                .thenReturn(result);

        ResponseEntity<Map<String, Object>> resp = controller.requestErasureMyData(USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("anonymized")).isEqualTo(true);
        assertThat(data.get("messagesErased")).isEqualTo(5);
        assertThat(data.get("conversationsErased")).isEqualTo(2);
    }
}
