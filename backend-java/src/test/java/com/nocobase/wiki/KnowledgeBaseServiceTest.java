package com.nocobase.wiki;

import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.auth.AclPolicyRepository;
import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserRoleRepository;
import com.nocobase.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeBaseServiceTest {

    @Autowired
    private KnowledgeBaseService service;

    @Autowired
    private KnowledgeBaseRepository repository;

    private final String tenantId = "test-tenant";
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void createAndGet() {
        KnowledgeBaseEntity kb = service.create("Test KB", "Description", "test-kb", "icon", userId, tenantId);
        assertNotNull(kb.getId());
        assertEquals("Test KB", kb.getName());
        assertEquals("test-kb", kb.getSlug());
        assertEquals(tenantId, kb.getTenantId());

        KnowledgeBaseEntity found = service.get(kb.getId());
        assertEquals(kb.getId(), found.getId());
    }

    @Test
    void createDuplicateSlugThrows() {
        service.create("KB1", "Desc", "test-kb", "icon", userId, tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.create("KB2", "Desc", "test-kb", "icon", userId, tenantId));
    }

    @Test
    void listByTenant() {
        service.create("KB1", "Desc", "kb1", "icon", userId, tenantId);
        service.create("KB2", "Desc", "kb2", "icon", userId, tenantId);
        service.create("KB3", "Desc", "kb3", "icon", userId, "other-tenant");

        List<KnowledgeBaseEntity> list = service.list(tenantId);
        assertEquals(2, list.size());
    }

    @Test
    void update() {
        KnowledgeBaseEntity kb = service.create("Original", "Desc", "orig", "icon", userId, tenantId);
        KnowledgeBaseEntity updated = service.update(kb.getId(), "Updated", "New Desc", "new-slug", "new-icon", tenantId);
        assertEquals("Updated", updated.getName());
        assertEquals("New Desc", updated.getDescription());
        assertEquals("new-slug", updated.getSlug());
        assertEquals("new-icon", updated.getIcon());
    }

    @Test
    void updateWrongTenantThrows() {
        KnowledgeBaseEntity kb = service.create("KB", "Desc", "kb", "icon", userId, tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.update(kb.getId(), "New", "Desc", "new", "icon", "other-tenant"));
    }

    @Test
    void delete() {
        KnowledgeBaseEntity kb = service.create("KB", "Desc", "kb", "icon", userId, tenantId);
        service.delete(kb.getId(), tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.get(kb.getId()));
    }

    @Test
    void deleteWrongTenantThrows() {
        KnowledgeBaseEntity kb = service.create("KB", "Desc", "kb", "icon", userId, tenantId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.delete(kb.getId(), "other-tenant"));
    }
}