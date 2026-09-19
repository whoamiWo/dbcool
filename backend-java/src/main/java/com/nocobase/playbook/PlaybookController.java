package com.nocobase.playbook;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playbook REST API(Phase 48 F2 — 此前 playbook 包无 Controller,前端 /api/playbooks 404)。
 *
 * <p>SecurityConfig anyRequest().authenticated() 兜底,自动需鉴权。
 */
@RestController
@Tag(name = "Playbooks", description = "剧本 — 定义 / 运行 / Checklist / SLA")
@RequestMapping("/api/playbooks")
public class PlaybookController {

    private final PlaybookService playbookService;

    public PlaybookController(PlaybookService playbookService) {
        this.playbookService = playbookService;
    }

    /** 列出剧本(status 为空或 ALL 时返回全部)。 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<PlaybookEntity> list = (status == null || status.isBlank())
                ? playbookService.list(user.tenantId())
                : playbookService.listByStatus(user.tenantId(), status);
        return Map.of("code", 0, "message", "success", "data", list);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", playbookService.get(id));
    }

    /** 创建剧本(定义非法直接 400,不静默存入)。 */
    @PostMapping
    public Map<String, Object> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        UUID channelId = parseUuid(body.get("channelId"));
        PlaybookEntity p = playbookService.create(
                user.tenantId(), channelId,
                String.valueOf(body.get("name")),
                body.get("description") == null ? null : String.valueOf(body.get("description")),
                String.valueOf(body.get("yamlSource")),
                user.username());
        return Map.of("code", 0, "message", "success", "data", p);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id,
                                      @RequestBody Map<String, Object> body) {
        PlaybookEntity p = playbookService.update(id,
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("description") == null ? null : String.valueOf(body.get("description")),
                body.get("yamlSource") == null ? null : String.valueOf(body.get("yamlSource")));
        return Map.of("code", 0, "message", "success", "data", p);
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable UUID id) {
        return Map.of("code", 0, "message", "success", "data", playbookService.activate(id));
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable UUID id) {
        return Map.of("code", 0, "message", "success", "data", playbookService.archive(id));
    }

    /** 运行一次(body 可带 slaHours,缺省 24h)。 */
    @PostMapping("/{id}/run")
    public Map<String, Object> run(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Integer slaHours = null;
        if (body != null && body.get("slaHours") instanceof Number n) {
            slaHours = n.intValue();
        }
        PlaybookRunEntity run = playbookService.runOnce(id, user.userId(), slaHours);
        return Map.of("code", 0, "message", "success", "data", run);
    }

    @GetMapping("/{id}/runs")
    public Map<String, Object> listRuns(@PathVariable UUID id) {
        return Map.of("code", 0, "message", "success", "data", playbookService.listRuns(id));
    }

    /** SLA 已逾期的运行(先做升级标记再返回升级列表)。 */
    @GetMapping("/runs/overdue")
    public Map<String, Object> overdue(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("runs", playbookService.markOverdue(user.tenantId())));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable UUID runId) {
        return Map.of("code", 0, "message", "success", "data", playbookService.getRun(runId));
    }

    /** 勾选 Checklist 项(body: {index, done})。 */
    @PutMapping("/runs/{runId}/checklist")
    public Map<String, Object> updateChecklist(
            @PathVariable UUID runId,
            @RequestBody Map<String, Object> body
    ) {
        if (!(body.get("index") instanceof Number idx)) {
            return Map.of("code", 1, "message", "index 必填");
        }
        boolean done = Boolean.parseBoolean(String.valueOf(body.get("done")));
        return Map.of("code", 0, "message", "success",
                "data", playbookService.updateChecklist(runId, idx.intValue(), done));
    }

    @PostMapping("/runs/{runId}/finish")
    public Map<String, Object> finish(@PathVariable UUID runId,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success",
                "data", playbookService.finishRun(runId, user.userId()));
    }

    private static UUID parseUuid(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        return UUID.fromString(String.valueOf(o));
    }
}
