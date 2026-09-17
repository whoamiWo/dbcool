package com.nocobase.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.FieldDef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 安装模板服务(US-410).
 *
 * <p>install(tenant, templateKey) 原子操作:
 * <ol>
 *   <li>跳过已存在的 collection</li>
 *   <li>创建模板定义的 collections(每个 template 可多个 collection)</li>
 *   <li>创建 workflow(节点、边、触发器)</li>
 * </ol>
 */
@Service
public class WorkflowTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateService.class);

    private final WorkflowTemplateRegistry registry;
    private final CollectionService collectionService;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper json = new ObjectMapper();

    public WorkflowTemplateService(WorkflowTemplateRegistry registry,
                                   CollectionService collectionService,
                                   WorkflowRepository workflowRepository) {
        this.registry = registry;
        this.collectionService = collectionService;
        this.workflowRepository = workflowRepository;
    }

    /**
     * 安装模板到指定 tenant.返回安装摘要(创建的 collections,workflow 等).
     */
    @Transactional
    public Map<String, Object> install(String tenantId, String templateKey, UUID installedBy) {
        WorkflowTemplate template = registry.get(templateKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "模板不存在: " + templateKey));

        List<String> createdCollections = new java.util.ArrayList<>();
        List<String> skippedCollections = new java.util.ArrayList<>();

        // 1. 创建 collections
        for (WorkflowTemplate.TemplateCollection c : template.collections()) {
            try {
                collectionService.get(c.name());
                skippedCollections.add(c.name());
            } catch (Exception notExists) {
                List<FieldDef> fields = c.fields().stream()
                        .map(f -> new FieldDef(
                                (String) f.get("name"),
                                (String) f.get("type"),
                                false,
                                (String) f.get("label"),
                                null,
                                false, // primaryKey
                                false, // unique
                                null   // defaultValue
                        ))
                        .toList();
                collectionService.create(c.name(), c.title(), c.description(),
                        fields, tenantId, installedBy);
                createdCollections.add(c.name());
            }
        }

        // 2. 创建 workflow
        WorkflowTemplate.TemplateWorkflow wf = template.workflow();
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName(wf.name());
        w.setTitle(wf.name());
        w.setDescription(wf.description());
        w.setCollectionName(wf.collection());
        try {
            w.setTriggerJson(json.writeValueAsString(wf.trigger()));
        } catch (Exception e) {
            w.setTriggerJson("{\"type\":\"manual\"}");
        }
        try {
            w.setNodesJson(json.writeValueAsString(wf.nodes()));
            w.setEdgesJson(json.writeValueAsString(wf.edges()));
        } catch (Exception e) {
            w.setNodesJson("[]");
            w.setEdgesJson("[]");
        }
        w.setEnabled(true);
        w.setTenantId(tenantId);
        w.setCreatedAt(Instant.now());
        w.setCreatedBy(installedBy);
        WorkflowEntity saved = workflowRepository.save(w);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_key", template.key());
        result.put("template_name", template.name());
        result.put("created_collections", createdCollections);
        result.put("skipped_collections", skippedCollections);
        result.put("workflow_id", saved.getId().toString());
        result.put("workflow_name", saved.getName());
        result.put("message", "模板已安装");

        log.info("模板 {} 安装: created={} skipped={} workflow={}",
                templateKey, createdCollections, skippedCollections, saved.getId());

        return result;
    }
}
