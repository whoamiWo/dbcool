package com.nocobase.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nocobase.approval.ApprovalCallbackEventEntity;
import com.nocobase.approval.ApprovalCallbackEventRepository;
import com.nocobase.approval.ApprovalExternalBindingEntity;
import com.nocobase.approval.ApprovalExternalBindingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("pg")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApprovalExternalBindingRepositoryPgIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalExternalBindingRepository bindings;
    @Autowired ApprovalCallbackEventRepository events;
    @Autowired com.nocobase.approval.ApprovalRequestRepository requests;
    private UUID requestId;

    @BeforeEach
    void schema() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS approval_callback_event");
        jdbc.execute("DROP TABLE IF EXISTS approval_external_binding");
        jdbc.execute("DROP TABLE IF EXISTS approval_request");
        jdbc.execute("DROP TABLE IF EXISTS approval_template");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
        jdbc.execute("""
                CREATE TABLE approval_template(id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                  name VARCHAR(255), icon VARCHAR(64), category VARCHAR(64), form_id VARCHAR(128),
                  workflow_id UUID NOT NULL, approver_type VARCHAR(32) NOT NULL DEFAULT 'USER',
                  external_process_code VARCHAR(128), enabled BOOLEAN NOT NULL DEFAULT TRUE,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())
                """);
        jdbc.execute("""
                CREATE TABLE approval_request(id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                  template_id UUID NOT NULL, applicant_id UUID NOT NULL, form_data JSONB NOT NULL DEFAULT '{}',
                  status VARCHAR(16) NOT NULL DEFAULT 'PENDING', instance_id UUID, current_task_id UUID,
                  external_instance_id VARCHAR(128), corp_id VARCHAR(128),
                  external_process_code VARCHAR(128),
                  submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), finished_at TIMESTAMPTZ)
                """);
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/V83__dingtalk_approval_external_binding.sql")));
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/V84__callback_event_corp_and_oidc_login_code.sql")));
        requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO approval_request(id,tenant_id,template_id,applicant_id) VALUES (?,?,?,?)",
                requestId, "tenant_pg_a", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void exactBindingAndUniqueCorpInstance_areEnforced() {
        ApprovalExternalBindingEntity binding = binding("oa-instance-pg-1");
        bindings.saveAndFlush(binding);
        assertThat(bindings.findByCorpIdAndProcessCodeAndProcessInstanceId(
                "corp-pg", "PROC-PG", "oa-instance-pg-1")).isPresent();
        assertThat(bindings.findByCorpIdAndProcessCodeAndProcessInstanceId(
                "corp-pg", "OTHER", "oa-instance-pg-1")).isEmpty();
        UUID otherRequest = UUID.randomUUID();
        jdbc.update("INSERT INTO approval_request(id,tenant_id,template_id,applicant_id) VALUES (?,?,?,?)",
                otherRequest, "tenant_pg_b", UUID.randomUUID(), UUID.randomUUID());
        ApprovalExternalBindingEntity duplicate = binding("oa-instance-pg-1");
        duplicate.setRequestId(otherRequest);
        duplicate.setTenantId("tenant_pg_b");
        assertThatThrownBy(() -> bindings.saveAndFlush(duplicate)).isInstanceOf(Exception.class);
    }

    @Test
    void pushClaimIsAtomicAndProcessingLeaseCanExpire() {
        jdbc.update("UPDATE approval_request SET status='PENDING', external_push_status='PENDING' WHERE id=?", requestId);
        assertThat(requests.claimPush(requestId)).isEqualTo(1);
        assertThat(requests.claimPush(requestId)).isZero();
    }

    @Test
    void eventClaimIsAtomicAndProcessedReplayDoesNotClaimAgain() {
        ApprovalExternalBindingEntity binding = bindings.saveAndFlush(binding("oa-instance-pg-2"));
        ApprovalCallbackEventEntity event = new ApprovalCallbackEventEntity();
        event.setCorpId("corp-pg");
        event.setEventId("event-pg-1");
        event.setBindingId(binding.getId());
        event.setTenantId(binding.getTenantId());
        event.setProcessInstanceId(binding.getProcessInstanceId());
        event.setResult("agree");
        event.setReceivedAt(Instant.now());
        events.saveAndFlush(event);
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-a")).isEqualTo(1);
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().plusSeconds(60), Instant.now(), "worker-lease")).isEqualTo(1);
        assertThat(events.finish(event.getCorpId(), event.getEventId(), "worker-lease", "FAILED", "retry", Instant.now())).isEqualTo(1);
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-a")).isEqualTo(1);
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-b")).isZero();
        assertThat(events.finish(event.getCorpId(), event.getEventId(), "worker-a", "PROCESSED", null, Instant.now())).isEqualTo(1);
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-b")).isZero();
        assertThat(events.findById(new com.nocobase.approval.ApprovalCallbackEventId(event.getCorpId(), event.getEventId()))).get()
                .extracting(ApprovalCallbackEventEntity::getProcessingStatus).isEqualTo("PROCESSED");
    }

    @Test
    void failedSameEventCanBeClaimedByAdminRetry() {
        ApprovalExternalBindingEntity binding = bindings.saveAndFlush(binding("oa-instance-pg-3"));
        ApprovalCallbackEventEntity event = new ApprovalCallbackEventEntity();
        event.setCorpId("corp-pg");
        event.setEventId("event-pg-failed");
        event.setBindingId(binding.getId());
        event.setTenantId(binding.getTenantId());
        event.setProcessInstanceId(binding.getProcessInstanceId());
        event.setResult("agree");
        events.saveAndFlush(event);
        events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-a");
        events.finish(event.getCorpId(), event.getEventId(), "worker-a", "FAILED", "business conflict", Instant.now());
        assertThat(events.claim(event.getCorpId(), event.getEventId(), Instant.now().minusSeconds(60), Instant.now(), "worker-retry")).isEqualTo(1);
    }

    @Test
    void upgradeFromV83BackfillsCorpAndAllowsSameEventIdAcrossCorps() throws Exception {
        jdbc.execute("DROP TABLE approval_callback_event");
        jdbc.execute("""
                CREATE TABLE approval_callback_event (
                  event_id VARCHAR(128) PRIMARY KEY,
                  binding_id UUID NOT NULL REFERENCES approval_external_binding(id),
                  tenant_id VARCHAR(64) NOT NULL,
                  process_instance_id VARCHAR(128) NOT NULL,
                  event_type VARCHAR(32) NOT NULL DEFAULT 'finish',
                  result VARCHAR(32) NOT NULL,
                  processing_status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
                  error VARCHAR(500), worker_id VARCHAR(128), claimed_at TIMESTAMPTZ,
                  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), processed_at TIMESTAMPTZ)
                """);
        var first = bindings.saveAndFlush(binding("oa-v83-a"));
        UUID secondRequest = UUID.randomUUID();
        jdbc.update("INSERT INTO approval_request(id,tenant_id,template_id,applicant_id) VALUES (?,?,?,?)",
                secondRequest, "tenant_pg_b", UUID.randomUUID(), UUID.randomUUID());
        var second = binding("oa-v83-b");
        second.setRequestId(secondRequest);
        second.setTenantId("tenant_pg_b");
        second.setCorpId("corp-v83-b");
        bindings.saveAndFlush(second);
        jdbc.update("INSERT INTO approval_callback_event(event_id,binding_id,tenant_id,process_instance_id,result) VALUES (?,?,?,?,?)",
                "legacy-shared-event", first.getId(), first.getTenantId(), first.getProcessInstanceId(), "agree");
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/V84__callback_event_corp_and_oidc_login_code.sql")));
        assertThat(jdbc.queryForObject("SELECT corp_id FROM approval_callback_event WHERE event_id='legacy-shared-event'",
                String.class)).isEqualTo(first.getCorpId());
        var newEvent = new ApprovalCallbackEventEntity();
        newEvent.setCorpId(second.getCorpId());
        newEvent.setEventId("legacy-shared-event");
        newEvent.setBindingId(second.getId());
        newEvent.setTenantId(second.getTenantId());
        newEvent.setProcessInstanceId(second.getProcessInstanceId());
        newEvent.setResult("agree");
        events.saveAndFlush(newEvent);
        assertThat(events.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                FROM pg_constraint c JOIN unnest(c.conkey) WITH ORDINALITY k(attnum,ord) ON true
                JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum
                WHERE c.conrelid='approval_callback_event'::regclass AND c.contype='p'
                """, String.class)).isEqualTo("corp_id,event_id");
    }

    @Test
    void sameEventIdInDifferentCorpHasIndependentIdempotency() {
        ApprovalExternalBindingEntity first = bindings.saveAndFlush(binding("oa-instance-corp-a"));
        UUID secondRequest = UUID.randomUUID();
        jdbc.update("INSERT INTO approval_request(id,tenant_id,template_id,applicant_id) VALUES (?,?,?,?)",
                secondRequest, "tenant_pg_b", UUID.randomUUID(), UUID.randomUUID());
        ApprovalExternalBindingEntity second = binding("oa-instance-corp-b");
        second.setRequestId(secondRequest);
        second.setTenantId("tenant_pg_b");
        second.setCorpId("corp-pg-b");
        bindings.saveAndFlush(second);
        for (var bound : List.of(first, second)) {
            var event = new ApprovalCallbackEventEntity();
            event.setCorpId(bound.getCorpId());
            event.setEventId("shared-event");
            event.setBindingId(bound.getId());
            event.setTenantId(bound.getTenantId());
            event.setProcessInstanceId(bound.getProcessInstanceId());
            event.setResult("agree");
            events.saveAndFlush(event);
            assertThat(events.claim(bound.getCorpId(), "shared-event",
                    Instant.now().minusSeconds(60), Instant.now(), "worker")).isEqualTo(1);
            assertThat(events.finish(bound.getCorpId(), "shared-event", "worker",
                    "PROCESSED", null, Instant.now())).isEqualTo(1);
        }
        assertThat(events.count()).isEqualTo(2);
    }

    private ApprovalExternalBindingEntity binding(String instanceId) {
        ApprovalExternalBindingEntity binding = new ApprovalExternalBindingEntity();
        binding.setId(UUID.randomUUID());
        binding.setTenantId("tenant_pg_a");
        binding.setRequestId(requestId);
        binding.setCorpId("corp-pg");
        binding.setProcessCode("PROC-PG");
        binding.setProcessInstanceId(instanceId);
        binding.setCreatedAt(Instant.now());
        return binding;
    }
}
