package com.nocobase.userdata;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * PHASE105: userdata 包 POJO 覆盖（UserDataErasureRequest / UserDataExport / UserDataExportRequest）。
 */
public class UserDataPojoTest {

    @Test
    void erasureRequest_gettersAndSetters() {
        UserDataErasureRequest r = new UserDataErasureRequest();
        r.setUserId("u1");
        r.setRequester("admin");
        r.setRequestedAt(Instant.now());
        r.setTenantId("tenant_default");
        r.setSuccess(true);
        r.setMessagesErased(10);
        r.setConversationsErased(2);
        r.setAuditLogsAnonymized(5);

        assertEquals("u1", r.getUserId());
        assertEquals("admin", r.getRequester());
        assertNotNull(r.getRequestedAt());
        assertEquals("tenant_default", r.getTenantId());
        assertTrue(r.isSuccess());
        assertEquals(10, r.getMessagesErased());
        assertEquals(2, r.getConversationsErased());
        assertEquals(5, r.getAuditLogsAnonymized());
    }

    @Test
    void erasureRequest_incrementMethods() {
        UserDataErasureRequest r = new UserDataErasureRequest();
        r.incrementMessagesErased(3);
        r.incrementConversationsErased(1);
        r.incrementAuditLogsAnonymized(4);
        assertEquals(3, r.getMessagesErased());
        assertEquals(1, r.getConversationsErased());
        assertEquals(4, r.getAuditLogsAnonymized());
    }

    @Test
    void export_gettersAndSetters() {
        UserDataExport e = new UserDataExport();
        e.setUserId("u1");
        e.setTenantId("tenant_default");
        e.setExportedAt(Instant.now());
        e.setExportedBy("admin");
        e.setUserData(Map.of("k", "v"));

        assertEquals("u1", e.getUserId());
        assertEquals("tenant_default", e.getTenantId());
        assertNotNull(e.getExportedAt());
        assertEquals("admin", e.getExportedBy());
        assertEquals("v", e.getUserData().get("k"));
    }

    @Test
    void exportRequest_recordAccessors() {
        UserDataExportRequest req = new UserDataExportRequest("full");
        assertEquals("full", req.exportType());
        assertNotNull(req.toString());
        assertEquals(req, new UserDataExportRequest("full"));
        assertEquals(req.hashCode(), new UserDataExportRequest("full").hashCode());
    }
}