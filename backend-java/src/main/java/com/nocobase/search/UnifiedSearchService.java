package com.nocobase.search;

import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiPageEntity;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.automation.AutomationRuleService;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.project.ProjectService;
import com.nocobase.project.ProjectTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跨模块统一搜索服务 — 参考 Algolia / Elasticsearch 设计。
 *
 * <p>核心变更（Week 52 收口）：搜索主路径改为**读 unified_search_index 索引表**，
 * 用 PostgreSQL FTS (tsvector/tsquery/ts_rank) 实现高性能检索，
 * 并用 ts_headline 生成可展示的高亮片段。
 *
 * <p>整合搜索范围:
 * <ul>
 *   <li>IM 消息（type=im）</li>
 *   <li>Wiki 页面（type=wiki）</li>
 *   <li>Collection 记录（type=record）</li>
 *   <li>自动化规则（type=automation）</li>
 *   <li>项目任务（type=project）</li>
 * </ul>
 *
 * <p>使用 PostgreSQL 全文搜索 (tsvector) + ts_headline 高亮。
 */
@Service
public class UnifiedSearchService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSearchService.class);

    private final MessageService messageService;
    private final WikiPageService wikiPageService;
    private final CollectionService collectionService;
    private final AutomationRuleService automationRuleService;
    private final ProjectService projectService;
    private final UnifiedSearchIndexRepository searchIndexRepository;
    private final ImChannelMemberRepository channelMemberRepository;
    private final ObjectMapper objectMapper;
    /** A-3：搜索侧必须与列表接口同源判权，不得"能搜到但打不开"。 */
    private final com.nocobase.auth.AclEnforcer aclEnforcer;
    private final com.nocobase.acl.RowAclService rowAclService;
    private final com.nocobase.auth.RoleRepository roleRepository;
    private final com.nocobase.wiki.WikiPermissionService wikiPermissionService;
    private final com.nocobase.approval.ApprovalRequestRepository approvalRequestRepository;
    private final com.nocobase.approval.ApprovalCcRepository approvalCcRepository;
    private final com.nocobase.workflow.WorkflowTaskRepository taskRepository;

    public UnifiedSearchService(
            MessageService messageService,
            WikiPageService wikiPageService,
            CollectionService collectionService,
            AutomationRuleService automationRuleService,
            ProjectService projectService,
            UnifiedSearchIndexRepository searchIndexRepository,
            ImChannelMemberRepository channelMemberRepository,
            ObjectMapper objectMapper,
            com.nocobase.auth.AclEnforcer aclEnforcer,
            com.nocobase.acl.RowAclService rowAclService,
            com.nocobase.auth.RoleRepository roleRepository,
            com.nocobase.wiki.WikiPermissionService wikiPermissionService,
            com.nocobase.approval.ApprovalRequestRepository approvalRequestRepository,
            com.nocobase.approval.ApprovalCcRepository approvalCcRepository,
            com.nocobase.workflow.WorkflowTaskRepository taskRepository
    ) {
        this.messageService = messageService;
        this.wikiPageService = wikiPageService;
        this.collectionService = collectionService;
        this.automationRuleService = automationRuleService;
        this.projectService = projectService;
        this.searchIndexRepository = searchIndexRepository;
        this.channelMemberRepository = channelMemberRepository;
        this.objectMapper = objectMapper;
        this.aclEnforcer = aclEnforcer;
        this.rowAclService = rowAclService;
        this.roleRepository = roleRepository;
        this.wikiPermissionService = wikiPermissionService;
        this.approvalRequestRepository = approvalRequestRepository;
        this.approvalCcRepository = approvalCcRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * 跨模块统一搜索。
     *
     * @param keyword  搜索关键词
     * @param tenantId 租户 ID
     * @param userId   当前用户 ID（用于 IM 频道成员权限过滤）
     * @param types    搜索类型列表(null=全部)
     * @param limit    结果数量限制
     * @return 搜索结果
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String keyword, String tenantId, UUID userId,
                                      List<String> types, int limit) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> allResults = new ArrayList<>();

        int safeLimit = Math.max(1, Math.min(limit, 100));
        int perTypeLimit = Math.max(1, safeLimit / 5);
        // T5.2：先放大取数窗口（×5），ACL 过滤后再截断，避免"先裁再过滤"少返回
        int fetchLimit = Math.min(safeLimit * 5, 200);
        int perTypeFetchLimit = Math.min(perTypeLimit * 5, 200);

        // 全部类型：走索引统一检索（IM 需按用户已加入频道过滤）
        if (types == null || types.isEmpty()) {
            allResults.addAll(searchIndex(tenantId, keyword, fetchLimit, userId));
        } else {
            for (String type : types) {
                allResults.addAll(searchByType(tenantId, keyword, type, perTypeFetchLimit, userId));
            }
        }

        // 按相关度分数排序（索引返回的 rank 已降序，这里做二次稳定排序）
        allResults.sort((a, b) -> {
            Object ra = a.get("rank");
            Object rb = b.get("rank");
            if (ra instanceof Number na && rb instanceof Number nb) {
                return Double.compare(nb.doubleValue(), na.doubleValue());
            }
            // 无 rank 时按创建时间倒序
            Object ta = a.get("createdAt");
            Object tb = b.get("createdAt");
            if (ta instanceof Instant ia && tb instanceof Instant ib) {
                return ib.compareTo(ia);
            }
            return 0;
        });

        result.put("keyword", keyword);
        result.put("total", allResults.size());
        result.put("results", allResults.subList(0, Math.min(allResults.size(), safeLimit)));
        result.put("facets", Map.of(
                "im", countByType(allResults, "im"),
                "wiki", countByType(allResults, "wiki"),
                "record", countByType(allResults, "record"),
                "project", countByType(allResults, "project"),
                "automation", countByType(allResults, "automation"),
                "approval", countByType(allResults, "approval")
        ));

        return result;
    }

    /**
     * 统一索引检索（无类型过滤）。
     */
    private List<Map<String, Object>> searchIndex(String tenantId, String keyword, int limit, UUID userId) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchAllTypes(keyword, tenantId, limit);
            List<Map<String, Object>> results = toResultMaps(hits);
            // IM 类型需按用户已加入频道过滤
            if (userId != null) {
                filterImByChannelMembership(results, userId);
            }
            // A-3：记录/文档必须做 ACL 过滤（修复前搜索返回无权记录 = 数据泄露）
            filterRecordsByAcl(results, tenantId, userId);
            filterWikiByAcl(results, tenantId, userId);
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] 全局索引检索失败 keyword={} tenantId={} error={}", keyword, tenantId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 按类型检索（走索引 + ts_headline 高亮）。
     */
    private List<Map<String, Object>> searchByType(String tenantId, String keyword, String type, int limit, UUID userId) {
        // E4：支持 record:<collection> 按集合过滤
        if (type != null && type.startsWith("record:")) {
            return searchRecord(tenantId, keyword, limit, userId, type.substring("record:".length()));
        }
        return switch (type) {
            case "wiki" -> searchWiki(tenantId, keyword, limit, userId);
            case "im" -> searchIm(tenantId, keyword, limit, userId);
            case "record" -> searchRecord(tenantId, keyword, limit, userId);
            case "automation" -> searchAutomation(tenantId, keyword, limit);
            case "project" -> searchProject(tenantId, keyword, limit);
            case "approval" -> searchApproval(tenantId, keyword, limit, userId);
            default -> List.of();
        };
    }

    // ============================================================
    //  各类型索引检索（统一走索引表 + ts_headline 高亮）
    // ============================================================

    /** Wiki 页面：走索引 + 权限过滤（仅 PUBLISHED 且用户有读权限的页面）。 */
    private List<Map<String, Object>> searchWiki(String tenantId, String keyword, int limit, UUID userId) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("wiki", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                // 权限过滤：仅返回 PUBLISHED 页面
                WikiPageEntity page = wikiPageService.get(UUID.fromString(idx.getEntityId()));
                if (page == null || !"PUBLISHED".equals(page.getStatus())) {
                    continue;
                }
                // A-3：修复前只判 PUBLISHED，不判读权限
                if (userId == null || !wikiPermissionService.hasPagePermission(
                        userId, tenantId, page.getId(),
                        com.nocobase.wiki.WikiPermissionService.Action.READ)) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                map.put("type", "wiki");
                map.put("id", page.getId());
                map.put("title", page.getTitle());
                map.put("snippet", idx.getSnippet());
                map.put("slug", page.getSlug());
                map.put("deeplink", "/wiki/" + page.getSlug());
                map.put("createdAt", page.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] wiki 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** IM 消息：走索引 + 频道成员过滤（防越权读取他频道消息）。 */
    private List<Map<String, Object>> searchIm(String tenantId, String keyword, int limit, UUID userId) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("im", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            // 预取用户已加入的频道 ID 集合，用于成员权限过滤
            Set<UUID> joinedChannelIds = (userId != null)
                    ? channelMemberRepository.findByTenantIdAndUserId(tenantId, userId)
                            .stream().map(m -> m.getChannelId()).collect(Collectors.toSet())
                    : null;
            for (UnifiedSearchIndexEntity idx : hits) {
                ImMessageEntity msg = messageService.mustGet(UUID.fromString(idx.getEntityId()));
                if (msg == null) continue;
                // 权限过滤：仅返回用户已加入的频道消息
                if (userId != null && joinedChannelIds != null && !joinedChannelIds.contains(msg.getChannelId())) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                map.put("type", "im");
                map.put("id", msg.getId());
                map.put("title", msg.getContent());
                map.put("snippet", idx.getSnippet());
                map.put("channelId", msg.getChannelId());
                map.put("createdAt", msg.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] im 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** Collection 记录：走索引检索 + ACL 过滤（A-3）。 */
    private List<Map<String, Object>> searchRecord(String tenantId, String keyword, int limit, UUID userId) {
        return searchRecord(tenantId, keyword, limit, userId, null);
    }

    private List<Map<String, Object>> searchRecord(String tenantId, String keyword, int limit,
                                                   UUID userId, String collection) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("record", keyword, tenantId, limit);
            List<Map<String, Object>> results = toResultMaps(hits);
            if (collection != null && !collection.isBlank()) {
                results.removeIf(r -> !collection.equals(String.valueOf(r.get("collection"))));
            }
            filterRecordsByAcl(results, tenantId, userId);
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] record 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** 自动化规则：走索引 + keyword 匹配（不再无条件全返回）。 */
    private List<Map<String, Object>> searchAutomation(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("automation", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                AutomationRuleEntity rule = automationRuleService.getRule(UUID.fromString(idx.getEntityId()));
                if (rule == null) continue;
                // 关键词匹配：规则名/描述
                String kw = keyword.toLowerCase();
                boolean hit = (rule.getName() != null && rule.getName().toLowerCase().contains(kw))
                        || (rule.getDescription() != null && rule.getDescription().toLowerCase().contains(kw));
                if (!hit) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("type", "automation");
                map.put("id", rule.getId());
                map.put("title", rule.getName());
                map.put("snippet", rule.getDescription() != null ? rule.getDescription() : "");
                map.put("triggerType", rule.getTriggerType());
                map.put("createdAt", rule.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] automation 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    /** 项目任务：走索引检索。 */
    private List<Map<String, Object>> searchProject(String tenantId, String keyword, int limit) {
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository.searchByKeywordAndType("project", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                ProjectTaskEntity task = projectService.get(UUID.fromString(idx.getEntityId()), tenantId);
                if (task == null) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("type", "project");
                map.put("id", task.getId());
                map.put("title", task.getTitle());
                map.put("snippet", idx.getSnippet());
                map.put("createdAt", task.getCreatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] project 索引检索失败 tenantId={} keyword={} error={}", tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    // ============================================================
    //  工具方法
    // ============================================================

    /** 将索引实体转为前端 SearchResult 结构。 */
    private List<Map<String, Object>> toResultMaps(List<UnifiedSearchIndexEntity> hits) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (UnifiedSearchIndexEntity idx : hits) {
            Map<String, Object> map = new HashMap<>();
            map.put("type", idx.getEntityType());
            map.put("id", idx.getEntityId());
            map.put("title", idx.getTitle());
            map.put("snippet", idx.getSnippet());
            map.put("rank", idx.getRank() != null ? idx.getRank() : 0);
            map.put("createdAt", idx.getUpdatedAt());
            Map<String, Object> meta = parseMetadata(idx.getMetadataJson());
            if (meta != null) {
                Object collection = meta.get("collection");
                if (collection != null) {
                    map.put("collection", collection);
                    map.put("deeplink", "/records/" + collection + "/" + idx.getEntityId());
                }
            }
            results.add(map);
        }
        return results;
    }

    /**
     * A-3：记录级 ACL —— 与 {@code GET /api/collections/{name}/records} 同源判定：
     * ①集合 READ 权限（AclEnforcer）②行级策略（RowAclService）③部门数据范围。
     *
     * <p>无法判定时一律丢弃（fail-closed），不允许"索引里有就返回"。
     */
    private void filterRecordsByAcl(List<Map<String, Object>> results, String tenantId, UUID userId) {
        results.removeIf(r -> {
            if (!"record".equals(r.get("type"))) return false;
            try {
                if (userId == null) return true;
                String collection = r.get("collection") == null ? null : r.get("collection").toString();
                if (collection == null || collection.isBlank()) return true; // 元数据缺失 → 无法判权
                if (!aclEnforcer.isAllowed(userId, tenantId, collection,
                        com.nocobase.auth.AclPolicyEntity.Action.READ)) {
                    return true;
                }
                String id = String.valueOf(r.get("id"));
                Map<String, Object> record = collectionService.getRecord(collection, id, tenantId);
                if (record == null) return true;
                List<String> roles = roleRepository.findRoleNamesByUserId(userId, tenantId);
                var principal = new com.nocobase.acl.RowAclService.Principal(
                        userId.toString(), roles);
                if (!rowAclService.evaluateRead(tenantId, collection, record, principal)) {
                    return true;
                }
                // 部门数据范围（A-1 修复后的真实子树判定）
                return aclEnforcer.filterRecordsByDataScope(userId, tenantId, collection,
                        List.of(record)).isEmpty();
            } catch (Exception e) {
                log.debug("[SEARCH] 记录判权失败，保守丢弃 id={}: {}", r.get("id"), e.getMessage());
                return true;
            }
        });
    }

    /** A-3：文档 ACL —— 仅返回 PUBLISHED 且用户有读权限的页面。 */
    private void filterWikiByAcl(List<Map<String, Object>> results, String tenantId, UUID userId) {
        results.removeIf(r -> {
            if (!"wiki".equals(r.get("type"))) return false;
            try {
                if (userId == null) return true;
                UUID pageId = UUID.fromString(String.valueOf(r.get("id")));
                return !wikiPermissionService.hasPagePermission(userId, tenantId, pageId,
                        com.nocobase.wiki.WikiPermissionService.Action.READ);
            } catch (Exception e) {
                return true;
            }
        });
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 审批单据（E2/E4）：仅返回申请人、抄送人、或当前/历史任务处理人可见的单据。
     */
    private List<Map<String, Object>> searchApproval(String tenantId, String keyword,
                                                     int limit, UUID userId) {
        if (userId == null) return List.of();
        try {
            List<UnifiedSearchIndexEntity> hits = searchIndexRepository
                    .searchByKeywordAndType("approval", keyword, tenantId, limit);
            List<Map<String, Object>> results = new ArrayList<>();
            for (UnifiedSearchIndexEntity idx : hits) {
                UUID requestId;
                try {
                    requestId = UUID.fromString(idx.getEntityId());
                } catch (Exception e) {
                    continue;
                }
                var request = approvalRequestRepository.findByIdAndTenantId(requestId, tenantId)
                        .orElse(null);
                if (request == null) continue;
                boolean allowed = request.getApplicantId().equals(userId)
                        || approvalCcRepository.findByRequestId(requestId).stream()
                                .anyMatch(cc -> cc.getUserId().equals(userId))
                        || (request.getInstanceId() != null
                                && taskRepository.existsByInstanceIdAndAssignee(
                                        request.getInstanceId(), userId));
                if (!allowed) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("type", "approval");
                map.put("id", requestId);
                map.put("title", idx.getTitle());
                map.put("snippet", idx.getSnippet());
                map.put("status", request.getStatus());
                map.put("deeplink", "/approvals/" + requestId);
                map.put("createdAt", idx.getUpdatedAt());
                results.add(map);
            }
            return results;
        } catch (Exception e) {
            log.warn("[SEARCH] approval 索引检索失败 tenantId={} keyword={} error={}",
                    tenantId, keyword, e.getMessage(), e);
            return List.of();
        }
    }

    private long countByType(List<Map<String, Object>> results, String type) {
        return results.stream().filter(r -> type.equals(r.get("type"))).count();
    }

    /**
     * 全局（无类型过滤）检索结果中的 IM 消息按频道成员关系过滤 — 防越权。
     *
     * <p>索引不带 channelId 列，需回表查消息取 channelId，再校验用户是否在该频道。
     */
    private void filterImByChannelMembership(List<Map<String, Object>> results, UUID userId) {
        results.removeIf(r -> {
            if (!"im".equals(r.get("type"))) return false;
            try {
                ImMessageEntity m = messageService.mustGet(UUID.fromString(String.valueOf(r.get("id"))));
                if (m == null) return true;
                return !channelMemberRepository.existsByChannelIdAndUserId(m.getChannelId(), userId);
            } catch (Exception e) {
                return true;
            }
        });
    }

    // ============================================================
    //  索引写入/删除（不变）
    // ============================================================

    /**
     * 写入统一搜索索引（增量更新）。
     */
    @Transactional
    public void indexEntity(String entityType, String entityId, String tenantId,
                            String title, String content, Map<String, Object> metadata) {
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(entityType, entityId, tenantId);
        UnifiedSearchIndexEntity index = new UnifiedSearchIndexEntity();
        index.setId(UUID.randomUUID());
        index.setEntityType(entityType);
        index.setEntityId(entityId);
        index.setTenantId(tenantId);
        index.setTitle(title);
        index.setContent(content);
        try {
            index.setMetadataJson(metadata != null ? objectMapper.writeValueAsString(metadata) : "{}");
        } catch (Exception e) {
            index.setMetadataJson("{}");
        }
        index.setCreatedAt(Instant.now());
        index.setUpdatedAt(Instant.now());
        searchIndexRepository.save(index);
    }

    /**
     * 从索引中移除实体。
     */
    @Transactional
    public void removeEntity(String entityType, String entityId, String tenantId) {
        searchIndexRepository.deleteByEntityTypeAndEntityIdAndTenantId(entityType, entityId, tenantId);
    }
}
