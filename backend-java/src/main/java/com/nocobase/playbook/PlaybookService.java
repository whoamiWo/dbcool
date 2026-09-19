package com.nocobase.playbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.im.MessageService;
import com.nocobase.wiki.KnowledgeBaseService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Playbook 折中版服务（Phase 48 F2 接真）。
 *
 * <p>运行实例由 {@link PlaybookRunEntity} 承载（Checklist / 事件 JSONB 整读整写 + SLA due_at）；
 * 定义源 {@code yamlSource} 为节点定义 JSON（顶层数组或 {nodes, edges, checklist} 对象），
 * <b>解析失败返回 400，禁止静默 fallback 生成假节点</b>；
 * 首次运行把定义编译为 WorkflowEntity 缓存到 {@code playbook.workflow_id}，后续复用，
 * 消除"每次运行 new WorkflowEntity 落库"的污染；执行复用 WorkflowEngine；
 * 完成后真正生成 Wiki 复盘页。
 */
@Service
public class PlaybookService {

    private static final int DEFAULT_SLA_HOURS = 24;

    private final PlaybookRepository playbookRepo;
    private final PlaybookRunRepository runRepo;
    private final WorkflowRepository workflowRepo;
    private final WorkflowInstanceRepository instanceRepo;
    private final WorkflowEngine workflowEngine;
    private final MessageService messageService;
    private final WikiPageService wikiPageService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public PlaybookService(PlaybookRepository playbookRepo,
                           PlaybookRunRepository runRepo,
                           WorkflowRepository workflowRepo,
                           WorkflowInstanceRepository instanceRepo,
                           WorkflowEngine workflowEngine,
                           MessageService messageService,
                           WikiPageService wikiPageService,
                           KnowledgeBaseService knowledgeBaseService,
                           ObjectMapper objectMapper) {
        this.playbookRepo = playbookRepo;
        this.runRepo = runRepo;
        this.workflowRepo = workflowRepo;
        this.instanceRepo = instanceRepo;
        this.workflowEngine = workflowEngine;
        this.messageService = messageService;
        this.wikiPageService = wikiPageService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    // -------------------- 定义 CRUD --------------------

    @Transactional
    public PlaybookEntity create(String tenantId, UUID channelId, String name,
                                 String description, String yamlSource, String createdBy) {
        // 定义非法在创建时就拒绝(而非静默存入垃圾)
        parseDefinition(yamlSource);
        PlaybookEntity p = new PlaybookEntity();
        p.setId(UUID.randomUUID());
        p.setTenantId(tenantId);
        p.setChannelId(channelId);
        p.setName(name);
        p.setDescription(description);
        p.setYamlSource(yamlSource);
        p.setStatus(PlaybookEntity.Status.DRAFT);
        p.setCreatedBy(createdBy);
        p.setCreatedAt(Instant.now());
        return playbookRepo.save(p);
    }

    @Transactional
    public PlaybookEntity update(UUID id, String name, String description, String yamlSource) {
        PlaybookEntity p = get(id);
        if (yamlSource != null) {
            parseDefinition(yamlSource);
            p.setYamlSource(yamlSource);
            p.setWorkflowId(null); // 定义变更,编译缓存失效
        }
        if (name != null) p.setName(name);
        if (description != null) p.setDescription(description);
        return playbookRepo.save(p);
    }

    @Transactional
    public PlaybookEntity activate(UUID id) {
        PlaybookEntity p = get(id);
        p.setStatus(PlaybookEntity.Status.ACTIVE);
        return playbookRepo.save(p);
    }

    @Transactional
    public PlaybookEntity archive(UUID id) {
        PlaybookEntity p = get(id);
        p.setStatus(PlaybookEntity.Status.ARCHIVED);
        return playbookRepo.save(p);
    }

    public PlaybookEntity get(UUID id) {
        return playbookRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "剧本不存在: " + id));
    }

    public List<PlaybookEntity> list(String tenantId) {
        return playbookRepo.findByTenantId(tenantId);
    }

    public List<PlaybookEntity> listByStatus(String tenantId, String status) {
        return playbookRepo.findByTenantIdAndStatus(tenantId, status);
    }

    public List<PlaybookEntity> listActiveByChannel(String tenantId, UUID channelId) {
        return playbookRepo.findActiveByChannel(tenantId, channelId);
    }

    // -------------------- 运行 --------------------

    /**
     * 运行一次剧本:创建 playbook_run + WorkflowInstance(复用缓存的 WorkflowEntity),
     * 同步执行节点图,启动消息同步到频道。
     */
    @Transactional
    public PlaybookRunEntity runOnce(UUID playbookId, UUID triggerUserId, Integer slaHours) {
        PlaybookEntity pb = get(playbookId);
        Definition def = parseDefinition(pb.getYamlSource());
        WorkflowEntity wf = ensureWorkflow(pb, def);
        // 实例承载执行
        WorkflowInstanceEntity inst = new WorkflowInstanceEntity();
        inst.setId(UUID.randomUUID());
        inst.setWorkflowId(wf.getId());
        inst.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        inst.setTriggerDataJson(toJson(Map.of(
                "playbookId", pb.getId().toString(),
                "triggeredBy", triggerUserId.toString())));
        inst.setStartedAt(Instant.now());
        inst.setCurrentNodeIndex(0);
        inst.setChannelId(pb.getChannelId());
        inst.setTenantId(pb.getTenantId());
        inst = instanceRepo.save(inst);
        // 运行记录(Checklist + SLA)
        PlaybookRunEntity run = new PlaybookRunEntity();
        run.setId(UUID.randomUUID());
        run.setTenantId(pb.getTenantId());
        run.setPlaybookId(pb.getId());
        run.setChannelId(pb.getChannelId());
        run.setInstanceId(inst.getId());
        run.setStatus(PlaybookRunEntity.RUNNING);
        int hours = slaHours != null && slaHours > 0 ? slaHours : DEFAULT_SLA_HOURS;
        run.setDueAt(Instant.now().plusSeconds((long) hours * 3600));
        run.setChecklistJson(toJson(def.initialChecklist()));
        run.setStartedAt(Instant.now());
        run = runRepo.save(run);
        // 执行(edges 为空走顺序模式)
        if (!def.edges().isEmpty() && !def.nodes().isEmpty()) {
            String startId = String.valueOf(def.nodes().get(0).get("id"));
            workflowEngine.executeGraphFrom(inst, def.nodes(), def.edges(), startId, triggerUserId);
        } else {
            workflowEngine.executeFrom(inst, def.nodes(), 0, triggerUserId);
        }
        appendEvent(run, "started", "由用户 " + triggerUserId + " 启动");
        syncProgress(pb, "剧本已启动: " + pb.getName(), triggerUserId);
        return runRepo.save(run);
    }

    public List<PlaybookRunEntity> listRuns(UUID playbookId) {
        return runRepo.findByPlaybookId(playbookId);
    }

    public PlaybookRunEntity getRun(UUID runId) {
        return runRepo.findById(runId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "运行实例不存在: " + runId));
    }

    /** 勾选 / 取消勾选 Checklist 项。 */
    @Transactional
    public PlaybookRunEntity updateChecklist(UUID runId, int index, boolean done) {
        PlaybookRunEntity run = getRun(runId);
        List<Map<String, Object>> items = readJson(run.getChecklistJson());
        if (index < 0 || index >= items.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checklist 下标越界: " + index);
        }
        items.get(index).put("done", done);
        run.setChecklistJson(toJson(items));
        appendEvent(run, "checklist_updated", "#" + (index + 1) + " → " + done);
        return runRepo.save(run);
    }

    /** 完成:标记 FINISHED + 生成 Wiki 复盘页(存在知识库时)+ 频道同步。 */
    @Transactional
    public PlaybookRunEntity finishRun(UUID runId, UUID userId) {
        PlaybookRunEntity run = getRun(runId);
        if (PlaybookRunEntity.FINISHED.equals(run.getStatus())) return run;
        run.setStatus(PlaybookRunEntity.FINISHED);
        run.setFinishedAt(Instant.now());
        PlaybookEntity pb = get(run.getPlaybookId());
        // 复盘页:租户第一个知识库(无 KB 时跳过并记事件)
        List<?> kbs = knowledgeBaseService.list(run.getTenantId());
        if (!kbs.isEmpty()) {
            var kb = (com.nocobase.wiki.KnowledgeBaseEntity) kbs.get(0);
            String slug = "playbook-retro-" + run.getId().toString().substring(0, 8);
            WikiPageEntity page = wikiPageService.create(
                    kb.getId(), null,
                    "复盘: " + pb.getName(), slug,
                    buildRetrospectiveContent(run, pb), userId, run.getTenantId());
            run.setRetrospectivePageId(page.getId());
        } else {
            appendEvent(run, "retrospective_skipped", "租户无知识库,未生成复盘页");
        }
        appendEvent(run, "finished", "耗时 "
                + (run.getFinishedAt().getEpochSecond() - run.getStartedAt().getEpochSecond()) + "s");
        syncProgress(pb, "剧本执行完成: " + pb.getName()
                + (run.getRetrospectivePageId() != null ? "(复盘页已生成)" : ""), userId);
        return runRepo.save(run);
    }

    /** SLA 到期升级:RUNNING 且 due_at 已过 → OVERDUE,返回本次升级的运行列表。 */
    @Transactional
    public List<PlaybookRunEntity> markOverdue(String tenantId) {
        List<PlaybookRunEntity> overdue = runRepo.findOverdue(tenantId, Instant.now());
        for (PlaybookRunEntity r : overdue) {
            r.setStatus(PlaybookRunEntity.OVERDUE);
            appendEvent(r, "sla_overdue", "SLA 到期未完成");
        }
        return runRepo.saveAll(overdue);
    }

    // -------------------- 内部 --------------------

    private WorkflowEntity ensureWorkflow(PlaybookEntity pb, Definition def) {
        if (pb.getWorkflowId() != null) {
            return workflowRepo.findById(pb.getWorkflowId()).orElseGet(() -> compileWorkflow(pb, def));
        }
        return compileWorkflow(pb, def);
    }

    /** 首次运行时编译定义为 WorkflowEntity 并缓存 workflow_id(仅一次)。 */
    private WorkflowEntity compileWorkflow(PlaybookEntity pb, Definition def) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(UUID.randomUUID());
        wf.setName(pb.getName());
        wf.setTriggerJson("{}");
        wf.setNodesJson(toJson(def.nodes()));
        wf.setEdgesJson(toJson(def.edges()));
        wf.setTenantId(pb.getTenantId());
        wf.setEnabled(true);
        wf = workflowRepo.save(wf);
        pb.setWorkflowId(wf.getId());
        playbookRepo.save(pb);
        return wf;
    }

    /** 解析定义 JSON;失败抛 400(禁止静默 fallback)。 */
    private Definition parseDefinition(String yamlSource) {
        if (yamlSource == null || yamlSource.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "剧本定义为空");
        }
        try {
            List<Map<String, Object>> nodes;
            List<Map<String, Object>> edges = List.of();
            List<Map<String, Object>> checklist = List.of();
            String trimmed = yamlSource.trim();
            if (trimmed.startsWith("[")) {
                nodes = objectMapper.readValue(trimmed, new TypeReference<>() {});
            } else {
                Map<String, Object> obj = objectMapper.readValue(trimmed, new TypeReference<>() {});
                Object n = obj.get("nodes");
                nodes = n instanceof List<?> l ? castList(l) : List.of();
                Object e = obj.get("edges");
                edges = e instanceof List<?> l ? castList(l) : List.of();
                Object c = obj.get("checklist");
                checklist = c instanceof List<?> l ? castList(l) : List.of();
            }
            if (nodes == null) nodes = List.of();
            return new Definition(nodes, edges, checklist);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "剧本定义解析失败(须为节点数组或 {nodes,edges,checklist} JSON): " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(List<?> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private void syncProgress(PlaybookEntity pb, String msg, UUID userId) {
        if (pb.getChannelId() == null) return;
        try {
            messageService.send(pb.getTenantId(), pb.getChannelId(), userId, msg, "text/plain", null);
        } catch (Exception e) {
            // 写路径异常吞掉,不影响主流程
        }
    }

    /** 追加事件(JSONB 整读整写,不为单条事件做插入)。 */
    private void appendEvent(PlaybookRunEntity run, String event, String detail) {
        try {
            List<Map<String, Object>> events = readJson(run.getEventsJson());
            Map<String, Object> e = new HashMap<>();
            e.put("at", Instant.now().toString());
            e.put("event", event);
            e.put("detail", detail);
            events.add(e);
            run.setEventsJson(toJson(events));
        } catch (Exception ignored) {
            // 事件追加失败不阻断主流程
        }
    }

    private String buildRetrospectiveContent(PlaybookRunEntity run, PlaybookEntity pb) {
        StringBuilder sb = new StringBuilder("# 复盘: ").append(pb.getName()).append("\n\n");
        sb.append("- 运行 ID: `").append(run.getId()).append("`\n");
        sb.append("- 状态: ").append(run.getStatus()).append("\n");
        sb.append("- 开始: ").append(run.getStartedAt()).append("\n");
        sb.append("- 结束: ").append(run.getFinishedAt()).append("\n\n");
        sb.append("## Checklist\n\n");
        for (Map<String, Object> item : readJson(run.getChecklistJson())) {
            sb.append("- [").append(Boolean.TRUE.equals(item.get("done")) ? "x" : " ").append("] ")
                    .append(item.getOrDefault("title", "")).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> readJson(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 解析后的定义(nodes 顺序执行 / edges 图执行 / checklist 初始项)。 */
    private record Definition(List<Map<String, Object>> nodes,
                             List<Map<String, Object>> edges,
                             List<Map<String, Object>> checklist) {

        private List<Map<String, Object>> initialChecklist() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> c : checklist) {
                Map<String, Object> item = new HashMap<>();
                item.put("title", String.valueOf(c.getOrDefault("title", "")));
                item.put("done", false);
                out.add(item);
            }
            return out;
        }
    }
}
