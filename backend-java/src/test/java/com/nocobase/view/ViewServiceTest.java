package com.nocobase.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * ViewService 单测(Week 31).
 * 覆盖 parseConfig 收尾 + 业务方法 create/update/get/delete/listByCollection/listAll.
 */
class ViewServiceTest {

    private ViewRepository repo;
    private ViewService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(ViewRepository.class);
        service = new ViewService(repo, json);
        when(repo.save(any(ViewEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ViewEntity makeView(String name) {
        ViewEntity v = new ViewEntity();
        v.setId(UUID.randomUUID());
        v.setName(name);
        v.setTitle(name + " title");
        v.setType(ViewEntity.Type.TABLE);
        v.setCollectionName("posts");
        v.setTenantId("tenant_default");
        v.setConfigJson("{}");
        v.setSharedWithJson("[]");
        v.setCreatedAt(Instant.now());
        v.setCreatedBy(UUID.randomUUID());
        return v;
    }

    @Test
    void create_normalCase_saves() {
        ViewEntity saved = service.create("posts", "v1", "View 1",
                ViewEntity.Type.TABLE, "{}", "[]", "tenant_default", UUID.randomUUID());
        assertEquals("posts", saved.getCollectionName());
        assertEquals("v1", saved.getName());
    }

    @Test
    void create_nullConfig_fallsBackToEmptyJson() {
        ViewEntity saved = service.create("posts", "v1", "T",
                ViewEntity.Type.TABLE, null, null, "tenant_default", UUID.randomUUID());
        assertEquals("{}", saved.getConfigJson());
        assertEquals("[]", saved.getSharedWithJson());
    }

    @Test
    void create_blankConfig_fallsBackToEmpty() {
        ViewEntity saved = service.create("posts", "v1", "T",
                ViewEntity.Type.TABLE, "   ", "", "tenant_default", UUID.randomUUID());
        assertEquals("{}", saved.getConfigJson());
        assertEquals("[]", saved.getSharedWithJson());
    }

    @Test
    void update_partialUpdate_appliesNonNull() {
        ViewEntity existing = makeView("old");
        when(repo.findByIdAndTenantId(existing.getId(), "tenant_default"))
                .thenReturn(Optional.of(existing));

        ViewEntity updated = service.update(existing.getId(), "new", "new title",
                null, null, "tenant_default");

        assertEquals("new", updated.getName());
        assertEquals("new title", updated.getTitle());
        assertEquals("{}", updated.getConfigJson());  // 不变
    }

    @Test
    void update_fullUpdate_appliesAll() {
        ViewEntity existing = makeView("old");
        when(repo.findByIdAndTenantId(existing.getId(), "tenant_default"))
                .thenReturn(Optional.of(existing));

        ViewEntity updated = service.update(existing.getId(), "new", "new title",
                "{\"k\":\"v\"}", "[\"u1\"]", "tenant_default");

        assertEquals("{\"k\":\"v\"}", updated.getConfigJson());
        assertEquals("[\"u1\"]", updated.getSharedWithJson());
    }

    @Test
    void update_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, "tenant_default")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(id, "x", null, null, null, "tenant_default"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void get_found_returnsEntity() {
        ViewEntity v = makeView("v1");
        when(repo.findByIdAndTenantId(v.getId(), "tenant_default")).thenReturn(Optional.of(v));
        assertEquals(v, service.get(v.getId(), "tenant_default"));
    }

    @Test
    void get_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, "tenant_default")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service.get(id, "tenant_default"));
    }

    @Test
    void listByCollection_delegatesToRepo() {
        when(repo.findByCollectionNameAndTenantIdOrderByCreatedAtDesc("posts", "tenant_default"))
                .thenReturn(List.of(makeView("v1"), makeView("v2")));
        assertEquals(2, service.listByCollection("posts", "tenant_default").size());
    }

    @Test
    void listAll_delegatesToRepo() {
        when(repo.findByTenantIdOrderByCreatedAtDesc("tenant_default"))
                .thenReturn(List.of(makeView("v1")));
        assertEquals(1, service.listAll("tenant_default").size());
    }

    @Test
    void delete_callsRepoDelete() {
        ViewEntity v = makeView("v1");
        when(repo.findByIdAndTenantId(v.getId(), "tenant_default")).thenReturn(Optional.of(v));
        service.delete(v.getId(), "tenant_default");
        org.mockito.Mockito.verify(repo).delete(v);
    }

    @Test
    void delete_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndTenantId(id, "tenant_default")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service.delete(id, "tenant_default"));
    }

    @Test
    void parseConfig_validJson_returnsMap() {
        ViewEntity v = makeView("v1");
        v.setConfigJson("{\"columns\":[\"id\",\"title\"],\"pageSize\":20}");
        Map<String, Object> cfg = service.parseConfig(v);
        assertEquals(2, ((List<?>) cfg.get("columns")).size());
        assertEquals(20, cfg.get("pageSize"));
    }

    @Test
    void parseConfig_invalidJson_throwsRuntimeException() {
        ViewEntity v = makeView("v1");
        v.setConfigJson("not valid json{");
        assertThrows(RuntimeException.class, () -> service.parseConfig(v));
    }
}
