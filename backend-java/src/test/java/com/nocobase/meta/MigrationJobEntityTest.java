package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationJobEntityTest {

    @Test
    void new_job_starts_pending() {
        MigrationJobEntity job = new MigrationJobEntity();
        assertEquals(MigrationJobEntity.Status.PENDING, job.getStatus());
    }

    @Test
    void can_set_all_fields() {
        MigrationJobEntity job = new MigrationJobEntity();
        UUID id = UUID.randomUUID();
        job.setId(id);
        job.setCollectionName("customer");
        job.setTenantId("t1");
        job.setOperation(MigrationJobEntity.Operation.ADD_FIELD);
        job.setPayloadJson("{\"name\":\"age\"}");

        assertEquals(id, job.getId());
        assertEquals("customer", job.getCollectionName());
        assertEquals(MigrationJobEntity.Operation.ADD_FIELD, job.getOperation());
        assertNotNull(job.getPayloadJson());
    }

    @Test
    void status_transitions() {
        MigrationJobEntity job = new MigrationJobEntity();
        assertEquals(MigrationJobEntity.Status.PENDING, job.getStatus());

        job.setStatus(MigrationJobEntity.Status.RUNNING);
        assertEquals(MigrationJobEntity.Status.RUNNING, job.getStatus());

        job.setStatus(MigrationJobEntity.Status.COMPLETED);
        assertEquals(MigrationJobEntity.Status.COMPLETED, job.getStatus());

        job.setStatus(MigrationJobEntity.Status.FAILED);
        job.setErrorMessage("lock timeout");
        assertEquals(MigrationJobEntity.Status.FAILED, job.getStatus());
        assertEquals("lock timeout", job.getErrorMessage());
    }

    @Test
    void operation_enum_has_all_values() {
        assertEquals(4, MigrationJobEntity.Operation.values().length);
        assertTrue(java.util.Arrays.asList(MigrationJobEntity.Operation.values())
                .contains(MigrationJobEntity.Operation.ADD_FIELD));
    }
}
