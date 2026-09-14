package com.nocobase.workflow;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WorkflowTemplateController WebMvcTest(Week 25 抬红线).
 * 覆盖 /api/workflow/templates 三个端点.
 */
@WebMvcTest(WorkflowTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowTemplateControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private WorkflowTemplateRegistry registry;
    @MockBean private WorkflowTemplateService installer;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private WorkflowTemplate template() {
        return new WorkflowTemplate(
                "leave_approval",
                "请假审批",
                "hr",
                "员工提交请假申请 → 经理审批",
                "📝",
                List.of(),
                new WorkflowTemplate.TemplateWorkflow(
                        "Leave Approval", "flow", "leave_request",
                        Map.of("type", "record.created"),
                        List.of(Map.of("id", "start", "type", "start")),
                        List.of(Map.of("source", "start", "target", "end"))
                )
        );
    }

    @Test
    void list_returnsAllTemplates() throws Exception {
        when(registry.list()).thenReturn(List.of(template()));

        mockMvc.perform(get("/api/workflow/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.templates[0].key").value("leave_approval"))
                .andExpect(jsonPath("$.data.templates[0].name").value("请假审批"))
                .andExpect(jsonPath("$.data.templates[0].category").value("hr"))
                .andExpect(jsonPath("$.data.templates[0].collections_count").value(0))
                .andExpect(jsonPath("$.data.templates[0].nodes_count").value(1));
    }

    @Test
    void get_existingTemplate_returnsDetails() throws Exception {
        when(registry.get("leave_approval")).thenReturn(java.util.Optional.of(template()));

        mockMvc.perform(get("/api/workflow/templates/leave_approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key").value("leave_approval"));
    }

    @Test
    void get_missingTemplate_returns404() throws Exception {
        when(registry.get("unknown")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/workflow/templates/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void install_createsWorkflow_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        loginAs(userId);
        when(installer.install(eq("tenant_default"), eq("leave_approval"), eq(userId)))
                .thenReturn(Map.of(
                        "workflow_id", UUID.randomUUID().toString(),
                        "template", "leave_approval",
                        "collections_created", List.of("leave_request"),
                        "nodes_created", 4
                ));

        mockMvc.perform(post("/api/workflow/templates/leave_approval/install"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("installed"))
                .andExpect(jsonPath("$.data.template").value("leave_approval"))
                .andExpect(jsonPath("$.data.nodes_created").value(4));
    }

    @Test
    void install_withoutAuth_returns5xx() throws Exception {
        // 无 AuthenticationPrincipal → user=null → NPE → 500
        mockMvc.perform(post("/api/workflow/templates/leave_approval/install"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    if (sc < 400 || sc > 599) {
                        throw new AssertionError("expected 4xx/5xx, got " + sc);
                    }
                });
    }
}
