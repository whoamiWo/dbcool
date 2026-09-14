package com.nocobase.view;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * ViewController MockMvc 测试(Week 24 批量 controller 测试).
 */
@WebMvcTest(ViewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ViewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private ViewService service;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;

    private void login(String userId, String username, String tenant) {
        AuthenticatedUser u = new AuthenticatedUser(UUID.fromString(userId), username, tenant);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private ViewEntity makeView(String id) {
        ViewEntity v = new ViewEntity();
        v.setId(UUID.fromString(id));
        v.setCollectionName("customer");
        v.setName("my_view");
        v.setTitle("我的视图");
        v.setType(ViewEntity.Type.TABLE);
        v.setConfigJson("{}");
        v.setSharedWithJson("[]");
        v.setTenantId("tenant_default");
        v.setCreatedAt(Instant.now());
        return v;
    }

    /* === GET /api/views === */

    @Test
    void list_noCollection_returnsAll() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        when(service.listAll("tenant_default")).thenReturn(List.of(
                makeView("00000000-0000-0000-0000-000000000010"),
                makeView("00000000-0000-0000-0000-000000000020")));

        mockMvc.perform(get("/api/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void list_withCollection_filtersByCollection() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        when(service.listByCollection(eq("customer"), eq("tenant_default")))
                .thenReturn(List.of(makeView("00000000-0000-0000-0000-000000000010")));

        mockMvc.perform(get("/api/views").param("collection", "customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /* === GET /api/views/{id} === */

    @Test
    void get_returnsViewWithConfigJson() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        ViewEntity v = makeView("00000000-0000-0000-0000-000000000010");
        v.setConfigJson("{\"pageSize\":50}");
        when(service.get(v.getId(), "tenant_default")).thenReturn(v);
        when(service.parseConfig(v)).thenReturn(Map.of("pageSize", 50));

        mockMvc.perform(get("/api/views/" + v.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("my_view"));
    }

    /* === POST /api/views === */

    @Test
    void create_valid_returns201() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        UUID newId = UUID.randomUUID();
        ViewEntity saved = makeView(newId.toString());
        when(service.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        String body = """
                {"collectionName":"customer","name":"new_view","title":"新视图","type":"table"}
                """;
        mockMvc.perform(post("/api/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("my_view"));
    }

    @Test
    void create_invalidType_returns400() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");

        String body = """
                {"collectionName":"customer","name":"new_view","type":"invalid_type"}
                """;
        mockMvc.perform(post("/api/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_invalidName_returns400() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        String body = """
                {"collectionName":"customer","name":"123_invalid","type":"table"}
                """;
        mockMvc.perform(post("/api/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /* === PUT /api/views/{id} === */

    @Test
    void update_returnsUpdatedView() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        UUID id = UUID.randomUUID();
        ViewEntity updated = makeView(id.toString());
        updated.setTitle("更新后");
        when(service.update(eq(id), any(), any(), any(), any(), eq("tenant_default")))
                .thenReturn(updated);

        mockMvc.perform(put("/api/views/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"更新后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("更新后"));
    }

    /* === DELETE /api/views/{id} === */

    @Test
    void delete_returnsCode0() throws Exception {
        login("00000000-0000-0000-0000-000000000001", "alice", "tenant_default");
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/views/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("deleted"));
    }
}