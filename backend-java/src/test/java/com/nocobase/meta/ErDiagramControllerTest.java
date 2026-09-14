package com.nocobase.meta;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * ErDiagramController WebMvcTest(Week 25 抬红线).
 * 覆盖 GET /api/admin/er-diagram:
 *   - 节点(nodes)
 *   - 边(edges,只保留 belongsTo/hasMany 关系)
 *   - 统计(stats)
 *   - 关联目标不存在时跳过该边
 */
@WebMvcTest(ErDiagramController.class)
@AutoConfigureMockMvc(addFilters = false)
class ErDiagramControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private CollectionService collectionService;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private CollectionMetaEntity collection(String name, String title, List<FieldDef> fields) {
        CollectionMetaEntity c = new CollectionMetaEntity();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setTitle(title);
        c.setTenantId("tenant_default");
        // fieldsJson 不重要(由 service 解析),但这里 mock parseFields 直接绕过
        c.setFieldsJson("[]");
        return c;
    }

    @Test
    void diagram_emptyTenant_returnsEmptyGraph() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        when(collectionService.list("tenant_default")).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andExpect(jsonPath("$.data.edges").isArray())
                .andExpect(jsonPath("$.data.stats.collections").value(0))
                .andExpect(jsonPath("$.data.stats.relationships").value(0));
    }

    @Test
    void diagram_singleCollection_noEdges() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        CollectionMetaEntity c = collection("user", "用户", List.of());
        when(collectionService.list("tenant_default")).thenReturn(List.of(c));
        when(collectionService.parseFields(c)).thenReturn(List.of(
                new FieldDef("name", "text", true, "姓名", null),
                new FieldDef("age", "number", false, "年龄", null)
        ));

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nodes[0].name").value("user"))
                .andExpect(jsonPath("$.data.nodes[0].title").value("用户"))
                .andExpect(jsonPath("$.data.nodes[0].field_count").value(2))
                .andExpect(jsonPath("$.data.nodes[0].field_types[0]").value("text"))
                .andExpect(jsonPath("$.data.edges").isEmpty())
                .andExpect(jsonPath("$.data.stats.collections").value(1));
    }

    @Test
    void diagram_belongsToRelation_createsEdge() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        CollectionMetaEntity user = collection("user", "用户", List.of());
        CollectionMetaEntity order = collection("order", "订单", List.of());
        when(collectionService.list("tenant_default")).thenReturn(List.of(user, order));
        when(collectionService.parseFields(user)).thenReturn(List.of());
        when(collectionService.parseFields(order)).thenReturn(List.of(
                new FieldDef("user", "belongsTo", false, "用户", Map.of("target", "user"))
        ));

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nodes[0].name").value("user"))
                .andExpect(jsonPath("$.data.nodes[1].name").value("order"))
                .andExpect(jsonPath("$.data.edges[0].source").value("order"))
                .andExpect(jsonPath("$.data.edges[0].target").value("user"))
                .andExpect(jsonPath("$.data.edges[0].field").value("user"))
                .andExpect(jsonPath("$.data.edges[0].type").value("belongsTo"))
                .andExpect(jsonPath("$.data.stats.relationships").value(1));
    }

    @Test
    void diagram_orphanTarget_skipsEdge() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        CollectionMetaEntity order = collection("order", "订单", List.of());
        when(collectionService.list("tenant_default")).thenReturn(List.of(order));
        when(collectionService.parseFields(order)).thenReturn(List.of(
                // target "ghost" 不在 nodes 中,应该被跳过
                new FieldDef("ghost_link", "belongsTo", false, "x", Map.of("target", "ghost"))
        ));

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.edges").isEmpty())
                .andExpect(jsonPath("$.data.stats.relationships").value(0));
    }

    @Test
    void diagram_fallsBackToCollectionOption() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        CollectionMetaEntity user = collection("user", "用户", List.of());
        CollectionMetaEntity post = collection("post", "文章", List.of());
        when(collectionService.list("tenant_default")).thenReturn(List.of(user, post));
        when(collectionService.parseFields(user)).thenReturn(List.of());
        when(collectionService.parseFields(post)).thenReturn(List.of(
                // 用 "collection" 键而非 "target" 键(兼容性)
                new FieldDef("author", "belongsTo", false, "作者", Map.of("collection", "user"))
        ));

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.edges[0].target").value("user"));
    }

    @Test
    void diagram_titleNull_fallsBackToName() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        CollectionMetaEntity c = new CollectionMetaEntity();
        c.setId(UUID.randomUUID());
        c.setName("thing");
        c.setTitle(null);
        c.setFieldsJson("[]");
        when(collectionService.list("tenant_default")).thenReturn(List.of(c));
        when(collectionService.parseFields(c)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/er-diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].title").value("thing"));
    }
}
