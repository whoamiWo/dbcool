package com.nocobase.playbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Playbook 控制器测试(Phase 48 F2):端点契约 + 参数夹取 + 异常路径。 */
class PlaybookControllerTest {

    private PlaybookService service;
    private PlaybookController controller;

    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", "tenant_default");
    private final UUID playbookId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PlaybookService.class);
        controller = new PlaybookController(service);
    }

    private PlaybookEntity playbook() {
        PlaybookEntity p = new PlaybookEntity();
        p.setId(playbookId);
        p.setTenantId("tenant_default");
        p.setName("值班响应");
        p.setYamlSource("[]");
        p.setStatus(PlaybookEntity.Status.ACTIVE);
        p.setCreatedAt(Instant.now());
        return p;
    }

    private PlaybookRunEntity run() {
        PlaybookRunEntity r = new PlaybookRunEntity();
        r.setId(UUID.randomUUID());
        r.setTenantId("tenant_default");
        r.setPlaybookId(playbookId);
        r.setStatus(PlaybookRunEntity.RUNNING);
        r.setDueAt(Instant.now().plusSeconds(3600));
        r.setChecklistJson("[{\"title\":\"止血\",\"done\":false}]");
        r.setEventsJson("[]");
        r.setStartedAt(Instant.now());
        return r;
    }

    @Test
    void list_all() {
        PlaybookEntity p = playbook();
        when(service.list("tenant_default")).thenReturn(List.of(p));
        Map<String, Object> resp = controller.list(null, user);
        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("data")).isEqualTo(List.of(p));
    }

    @Test
    void list_byStatus() {
        when(service.listByStatus("tenant_default", "ACTIVE")).thenReturn(List.of(playbook()));
        Map<String, Object> resp = controller.list("ACTIVE", user);
        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void getById() {
        when(service.get(playbookId)).thenReturn(playbook());
        assertThat(controller.get(playbookId, user).get("code")).isEqualTo(0);
        verify(service).get(playbookId);
    }

    @Test
    void create_passesBody() {
        when(service.create(eq("tenant_default"), isNull(), eq("值班响应"),
                isNull(), eq("[]"), eq("alice"))).thenReturn(playbook());
        Map<String, Object> resp = controller.create(Map.of(
                "name", "值班响应", "yamlSource", "[]"), user);
        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void create_invalidDefinitionPropagates400() {
        when(service.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "剧本定义解析失败"));
        assertThatThrownBy(() -> controller.create(Map.of(
                "name", "x", "yamlSource", "not-json"), user))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void update() {
        when(service.update(eq(playbookId), isNull(), isNull(), eq("[]"))).thenReturn(playbook());
        assertThat(controller.update(playbookId, Map.of("yamlSource", "[]")).get("code")).isEqualTo(0);
    }

    @Test
    void activate_and_archive() {
        when(service.activate(playbookId)).thenReturn(playbook());
        when(service.archive(playbookId)).thenReturn(playbook());
        assertThat(controller.activate(playbookId).get("code")).isEqualTo(0);
        assertThat(controller.archive(playbookId).get("code")).isEqualTo(0);
        verify(service).activate(playbookId);
        verify(service).archive(playbookId);
    }

    @Test
    void run_defaultsSlaAndReturnsRun() {
        PlaybookRunEntity r = run();
        when(service.runOnce(eq(playbookId), eq(userId), isNull())).thenReturn(r);
        Map<String, Object> resp = controller.run(playbookId, null, user);
        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("data")).isEqualTo(r);
    }

    @Test
    void run_withSlaHours() {
        when(service.runOnce(playbookId, userId, 48)).thenReturn(run());
        controller.run(playbookId, Map.of("slaHours", 48), user);
        verify(service).runOnce(playbookId, userId, 48);
    }

    @Test
    void listRuns_and_getRun() {
        when(service.listRuns(playbookId)).thenReturn(List.of(run()));
        assertThat(controller.listRuns(playbookId).get("code")).isEqualTo(0);

        UUID runId = UUID.randomUUID();
        when(service.getRun(runId)).thenReturn(run());
        assertThat(controller.getRun(runId).get("code")).isEqualTo(0);
    }

    @Test
    void checklist_update() {
        PlaybookRunEntity r = run();
        when(service.updateChecklist(r.getId(), 0, true)).thenReturn(r);
        Map<String, Object> resp = controller.updateChecklist(r.getId(),
                Map.of("index", 0, "done", true));
        assertThat(resp.get("code")).isEqualTo(0);
        verify(service).updateChecklist(r.getId(), 0, true);
    }

    @Test
    void checklist_missingIndexRejected() {
        Map<String, Object> resp = controller.updateChecklist(UUID.randomUUID(), Map.of("done", true));
        assertThat(resp.get("code")).isEqualTo(1);
    }

    @Test
    void finish() {
        PlaybookRunEntity r = run();
        when(service.finishRun(r.getId(), userId)).thenReturn(r);
        assertThat(controller.finish(r.getId(), user).get("code")).isEqualTo(0);
        verify(service).finishRun(r.getId(), userId);
    }

    @Test
    void overdue_marksAndReturns() {
        when(service.markOverdue("tenant_default")).thenReturn(List.of(run()));
        Map<String, Object> resp = controller.overdue(user);
        assertThat(resp.get("code")).isEqualTo(0);
        verify(service).markOverdue("tenant_default");
    }
}
