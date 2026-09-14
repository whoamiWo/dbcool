package com.nocobase.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;

/**
 * UserAdminController WebMvcTest(Week 26 抬红线).
 * 覆盖 /api/admin/users 9 个端点:
 *   GET    /api/admin/users
 *   GET    /api/admin/users/{id}
 *   POST   /api/admin/users
 *   PATCH  /api/admin/users/{id}
 *   POST   /api/admin/users/{id}/password
 *   DELETE /api/admin/users/{id}
 *   POST   /api/admin/users/{id}/roles/{roleId}
 *   DELETE /api/admin/users/{id}/roles/{roleId}
 *   GET    /api/admin/users/{id}/effective-permissions
 */
@WebMvcTest(UserAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private UserAdminService userService;
    @MockBean private UserRoleRepository userRoleRepository;

    private UserEntity user(UUID id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName("Display " + username);
        u.setTenantId("tenant_default");
        u.setEnabled(true);
        u.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return u;
    }

    private RoleEntity role(UUID id, String name) {
        RoleEntity r = new RoleEntity();
        r.setId(id);
        r.setName(name);
        r.setDescription(name + " desc");
        return r;
    }

    @Test
    void list_returnsAllUsers() throws Exception {
        UUID u1 = UUID.randomUUID();
        when(userService.listAll()).thenReturn(List.of(user(u1, "alice"), user(UUID.randomUUID(), "bob")));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(u1.toString()))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].display_name").value("Display alice"))
                .andExpect(jsonPath("$.data[0].enabled").value(true));
    }

    @Test
    void get_returnsUserWithRoles() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        when(userService.get(uid)).thenReturn(user(uid, "alice"));
        when(userService.getUserRoles(uid)).thenReturn(List.of(role(rid, "admin")));

        mockMvc.perform(get("/api/admin/users/{id}", uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.roles[0].name").value("admin"))
                .andExpect(jsonPath("$.data.roles[0].id").value(rid.toString()));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        UUID uid = UUID.randomUUID();
        when(userService.create(eq("alice"), eq("pw"), any())).thenReturn(user(uid, "alice"));

        String body = """
                {"username":"alice","password":"pw","displayName":"Alice"}
                """;
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void create_blankUsername_returns400() throws Exception {
        String body = """
                {"username":"","password":"pw","displayName":"X"}
                """;
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_partialFields_returnsUpdatedUser() throws Exception {
        UUID uid = UUID.randomUUID();
        when(userService.update(eq(uid), eq("New Name"), eq(false)))
                .thenReturn(user(uid, "alice"));

        String body = """
                {"displayName":"New Name","enabled":false}
                """;
        mockMvc.perform(patch("/api/admin/users/{id}", uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void resetPassword_returnsSuccess() throws Exception {
        UUID uid = UUID.randomUUID();
        doNothing().when(userService).resetPassword(eq(uid), eq("newpw"));

        String body = """
                {"password":"newpw"}
                """;
        mockMvc.perform(post("/api/admin/users/{id}/password", uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("password reset"));
    }

    @Test
    void delete_removesUserAndRoles() throws Exception {
        UUID uid = UUID.randomUUID();
        doNothing().when(userService).delete(uid);
        doNothing().when(userRoleRepository).deleteByIdUserId(uid);

        mockMvc.perform(delete("/api/admin/users/{id}", uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("deleted"));
    }

    @Test
    void assignRole_returnsAssigned() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        doNothing().when(userService).assignRole(uid, rid);

        mockMvc.perform(post("/api/admin/users/{id}/roles/{roleId}", uid, rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("assigned"));
    }

    @Test
    void removeRole_returnsRemoved() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        doNothing().when(userService).removeRole(uid, rid);

        mockMvc.perform(delete("/api/admin/users/{id}/roles/{roleId}", uid, rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("removed"));
    }

    @Test
    void effectivePermissions_returnsData() throws Exception {
        UUID uid = UUID.randomUUID();
        when(userService.getEffectivePermissions(uid)).thenReturn(Map.of(
                "permissions", List.of("read", "write"),
                "source", Map.of("role", "admin")
        ));

        mockMvc.perform(get("/api/admin/users/{id}/effective-permissions", uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.permissions[0]").value("read"))
                .andExpect(jsonPath("$.data.source.role").value("admin"));
    }
}
