package com.nocobase.form;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * FormController WebMvcTest(Week 26 抬红线).
 * 覆盖 /api/forms 5 个端点 + parseLayout/parseRules.
 */
@WebMvcTest(FormController.class)
@AutoConfigureMockMvc(addFilters = false)
class FormControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private FormService service;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private FormEntity form(UUID id, String title) {
        FormEntity f = new FormEntity();
        f.setId(id);
        f.setCollectionName("customer");
        f.setTitle(title);
        f.setDescription("desc");
        f.setLayoutJson("[{\"type\":\"input\",\"name\":\"name\"}]");
        f.setRulesJson("{\"required\":[\"name\"]}");
        f.setTenantId("tenant_default");
        f.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        f.setUpdatedAt(Instant.parse("2026-09-02T10:00:00Z"));
        return f;
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID fid = UUID.randomUUID();
        loginAs(uid);
        when(service.create(eq("customer"), eq("客户"), any(), any(), any(), eq("tenant_default"), eq(uid)))
                .thenReturn(form(fid, "客户"));

        String body = """
                {"collectionName":"customer","title":"客户","description":"d","layout":"[]","rules":"{}"}
                """;
        mockMvc.perform(post("/api/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("客户"));
    }

    @Test
    void create_blankCollectionName_returns400() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        String body = """
                {"collectionName":"","title":"客户"}
                """;
        mockMvc.perform(post("/api/forms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_all_returnsAllForms() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        when(service.listAll("tenant_default")).thenReturn(List.of(form(UUID.randomUUID(), "客户"), form(UUID.randomUUID(), "订单")));

        mockMvc.perform(get("/api/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void list_byCollection_filters() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        when(service.listByCollection("customer", "tenant_default"))
                .thenReturn(List.of(form(UUID.randomUUID(), "客户")));

        mockMvc.perform(get("/api/forms").param("collection", "customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("客户"));
    }

    @Test
    void get_returnsFormWithParsedLayoutAndRules() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID fid = UUID.randomUUID();
        loginAs(uid);
        FormEntity f = form(fid, "客户");
        when(service.get(fid, "tenant_default")).thenReturn(f);
        when(service.parseLayout(f)).thenReturn(List.of(Map.of("type", "input", "name", "name")));
        when(service.parseRules(f)).thenReturn(Map.of("required", List.of("name")));

        mockMvc.perform(get("/api/forms/{id}", fid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("客户"))
                .andExpect(jsonPath("$.data.layout[0].type").value("input"))
                .andExpect(jsonPath("$.data.rules.required[0]").value("name"));
    }

    @Test
    void update_returnsUpdated() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID fid = UUID.randomUUID();
        loginAs(uid);
        when(service.update(eq(fid), eq("新"), any(), any(), any(), eq("tenant_default")))
                .thenReturn(form(fid, "新"));

        String body = """
                {"title":"新"}
                """;
        mockMvc.perform(put("/api/forms/{id}", fid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("新"));
    }

    @Test
    void delete_returnsSuccess() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID fid = UUID.randomUUID();
        loginAs(uid);
        doNothing().when(service).delete(fid, "tenant_default");

        mockMvc.perform(delete("/api/forms/{id}", fid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("deleted"));
    }
}
