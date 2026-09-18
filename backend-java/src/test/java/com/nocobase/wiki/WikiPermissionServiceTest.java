package com.nocobase.wiki;

import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WikiPermissionServiceTest {

    @Mock
    private AclEnforcer aclEnforcer;

    @InjectMocks
    private WikiPermissionService permissionService;

    @Test
    void testHasKbPermission_WithRoles() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        UUID kbId = UUID.randomUUID();
        WikiPermissionService.Action action = WikiPermissionService.Action.READ;

        when(aclEnforcer.loadRoleIdsIncludingInheritance(userId, tenantId))
                .thenReturn(List.of(UUID.randomUUID()));
        when(aclEnforcer.isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), any(AclPolicyEntity.Action.class)))
                .thenReturn(true);

        boolean result = permissionService.hasKbPermission(userId, tenantId, kbId, action);

        assertTrue(result);
        verify(aclEnforcer).isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), eq(AclPolicyEntity.Action.READ));
    }

    @Test
    void testHasKbPermission_WithoutRoles() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        UUID kbId = UUID.randomUUID();
        WikiPermissionService.Action action = WikiPermissionService.Action.READ;

        when(aclEnforcer.loadRoleIdsIncludingInheritance(userId, tenantId))
                .thenReturn(List.of());

        boolean result = permissionService.hasKbPermission(userId, tenantId, kbId, action);

        assertTrue(result); // 没有角色时默认允许
        verify(aclEnforcer, never()).isAllowed(any(), any(), any(), any());
    }

    @Test
    void testAssertKbPermission_WithPermission() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        UUID kbId = UUID.randomUUID();
        WikiPermissionService.Action action = WikiPermissionService.Action.READ;

        when(aclEnforcer.loadRoleIdsIncludingInheritance(userId, tenantId))
                .thenReturn(List.of(UUID.randomUUID()));
        when(aclEnforcer.isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), any(AclPolicyEntity.Action.class)))
                .thenReturn(true);

        assertDoesNotThrow(() -> {
            permissionService.assertKbPermission(userId, tenantId, kbId, action);
        });

        verify(aclEnforcer).isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), eq(AclPolicyEntity.Action.READ));
    }

    @Test
    void testAssertKbPermission_WithoutPermission() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        UUID kbId = UUID.randomUUID();
        WikiPermissionService.Action action = WikiPermissionService.Action.READ;

        when(aclEnforcer.loadRoleIdsIncludingInheritance(userId, tenantId))
                .thenReturn(List.of(UUID.randomUUID()));
        when(aclEnforcer.isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), any(AclPolicyEntity.Action.class)))
                .thenReturn(false);

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> {
            permissionService.assertKbPermission(userId, tenantId, kbId, action);
        });

        verify(aclEnforcer).isAllowed(eq(userId), eq(tenantId), eq("knowledge_base"), eq(AclPolicyEntity.Action.READ));
    }

    @Test
    void testFilterWritableFields() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        WikiPermissionService.Action action = WikiPermissionService.Action.UPDATE;

        Map<String, Object> mockFields = new HashMap<>();
        mockFields.put("title", "test");

        when(aclEnforcer.filterWritableFields(eq(userId), eq(tenantId), eq("wiki_page"), any(AclPolicyEntity.Action.class)))
                .thenReturn(mockFields.keySet());

        var result = permissionService.filterWritableFields(userId, tenantId, "wiki_page", action);

        assertNotNull(result);
        verify(aclEnforcer).filterWritableFields(eq(userId), eq(tenantId), eq("wiki_page"), eq(AclPolicyEntity.Action.UPDATE));
    }

    @Test
    void testFilterPageRecord() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        Map<String, Object> record = new HashMap<>();
        record.put("title", "test");
        record.put("content", "content");

        when(aclEnforcer.filterRecord(eq(userId), eq(tenantId), eq("wiki_page"), eq(record)))
                .thenReturn(record);

        Map<String, Object> result = permissionService.filterPageRecord(userId, tenantId, record);

        assertNotNull(result);
        verify(aclEnforcer).filterRecord(userId, tenantId, "wiki_page", record);
    }

    @Test
    void testFilterKbRecord() {
        UUID userId = UUID.randomUUID();
        String tenantId = "tenant1";
        Map<String, Object> record = new HashMap<>();
        record.put("name", "test KB");

        when(aclEnforcer.filterRecord(eq(userId), eq(tenantId), eq("knowledge_base"), eq(record)))
                .thenReturn(record);

        Map<String, Object> result = permissionService.filterKbRecord(userId, tenantId, record);

        assertNotNull(result);
        verify(aclEnforcer).filterRecord(userId, tenantId, "knowledge_base", record);
    }
}