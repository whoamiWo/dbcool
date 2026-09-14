package com.nocobase.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NotificationChannelController MockMvc 测试(Week 24).
 */
@WebMvcTest(NotificationChannelController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationChannelControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private NotificationChannelRepository repository;
    @MockBean private NotificationService service;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;

    private static final String TENANT = "tenant_default";

    private void login() {
        AuthenticatedUser u = new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private NotificationChannelEntity makeChannel(UUID id, String tenant) {
        NotificationChannelEntity e = new NotificationChannelEntity();
        e.setId(id);
        e.setTenantId(tenant);
        e.setType(NotificationChannelEntity.Type.EMAIL);
        e.setName("test_ch");
        e.setConfig(new HashMap<>(Map.of("smtp_host", "smtp.example.com")));
        e.setEnabled(true);
        e.setCreatedAt(Instant.now());
        return e;
    }

    /* === GET === */

    @Test
    void list_returnsAllChannelsInTenant() throws Exception {
        login();
        when(repository.findByTenantId(TENANT)).thenReturn(List.of(
                makeChannel(UUID.randomUUID(), TENANT),
                makeChannel(UUID.randomUUID(), TENANT)));

        mockMvc.perform(get("/api/admin/notification-channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void list_emptyTenant_returnsEmpty() throws Exception {
        login();
        when(repository.findByTenantId(TENANT)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/notification-channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /* === POST === */

    @Test
    void create_valid_returns200() throws Exception {
        login();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {"type":"EMAIL","name":"new_ch","config":{"smtp_host":"smtp.example.com"}}
                """;
        mockMvc.perform(post("/api/admin/notification-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("new_ch"));
    }

    /* === PUT === */

    @Test
    void update_existingInSameTenant_returns200() throws Exception {
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(makeChannel(id, TENANT)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/admin/notification-channels/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void update_crossTenant_returns403() throws Exception {
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(makeChannel(id, "other_tenant")));

        mockMvc.perform(put("/api/admin/notification-channels/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void update_notFound_throwsIAE_translatedTo400() throws Exception {
        // IllegalArgumentException 由 Spring 默认翻译为 400(Bad Request)
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/admin/notification-channels/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isBadRequest());
    }

    /* === DELETE === */

    @Test
    void delete_existingInSameTenant_returns200() throws Exception {
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(makeChannel(id, TENANT)));

        mockMvc.perform(delete("/api/admin/notification-channels/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void delete_crossTenant_returns403() throws Exception {
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(makeChannel(id, "other_tenant")));

        mockMvc.perform(delete("/api/admin/notification-channels/" + id))
                .andExpect(status().isForbidden());
    }

    /* === POST test === */

    @Test
    void test_send_returnsResult() throws Exception {
        login();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(makeChannel(id, TENANT)));
        when(service.testSend(any(), any(), any()))
                .thenReturn(NotificationDispatcher.SendResult.ok("sent"));

        mockMvc.perform(post("/api/admin/notification-channels/" + id + "/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"u@e.com\",\"title\":\"Hi\",\"body\":\"World\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    /* === GET types === */

    @Test
    void types_returnsAvailableTypes() throws Exception {
        login();

        mockMvc.perform(get("/api/admin/notification-channels/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}