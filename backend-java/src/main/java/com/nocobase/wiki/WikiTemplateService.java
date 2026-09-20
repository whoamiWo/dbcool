package com.nocobase.wiki;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Wiki 模板服务 — 模板 CRUD + 从模板创建页面。
 */
@Service
public class WikiTemplateService {

    private final WikiTemplateRepository templateRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final WikiPageService pageService;
    private final WikiBlockService blockService;

    @Autowired
    public WikiTemplateService(WikiTemplateRepository templateRepository,
                               KnowledgeBaseService knowledgeBaseService,
                               WikiPageService pageService,
                               WikiBlockService blockService) {
        this.templateRepository = templateRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.pageService = pageService;
        this.blockService = blockService;
    }

    /**
     * 在知识库下创建模板。
     */
    @Transactional
    public WikiTemplateEntity createTemplate(UUID kbId, String name, String title,
                                              List<Map<String, Object>> blocks,
                                              String icon, String tenantId, UUID createdBy) {
        knowledgeBaseService.get(kbId); // 验证知识库存在
        WikiTemplateEntity tpl = new WikiTemplateEntity();
        tpl.setId(UUID.randomUUID());
        tpl.setKbId(kbId);
        tpl.setName(name);
        tpl.setTitle(title);
        tpl.setContentJson(blocks != null ? blocks.toString() : "[]");
        tpl.setIcon(icon);
        tpl.setTenantId(tenantId);
        tpl.setCreatedBy(createdBy);
        tpl.setCreatedAt(Instant.now());
        tpl.setUpdatedAt(Instant.now());
        return templateRepository.save(tpl);
    }

    /**
     * 列出知识库下的所有模板。
     */
    public List<WikiTemplateEntity> listTemplates(UUID kbId) {
        return templateRepository.findByKbId(kbId);
    }

    /**
     * 获取模板详情。
     */
    public WikiTemplateEntity getTemplate(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
    }

    /**
     * 删除模板。
     */
    @Transactional
    public void deleteTemplate(UUID id, String tenantId) {
        WikiTemplateEntity tpl = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        if (!tpl.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除");
        }
        templateRepository.delete(tpl);
    }

    /**
     * 从模板创建新页面（Block 树直接复制）。
     */
    @Transactional
    public WikiPageEntity createPageFromTemplate(UUID templateId, UUID kbId, UUID parentId,
                                                  String title, String slug, UUID createdBy, String tenantId) {
        WikiTemplateEntity tpl = getTemplate(templateId);
        if (!tpl.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用模板");
        }
        // 创建页面（content 留空，Block 树单独存储）
        WikiPageEntity page = pageService.create(kbId, parentId, title, slug, "", createdBy, tenantId);
        // 复制 Block 树
        if (tpl.getContentJson() != null && !tpl.getContentJson().isEmpty()) {
            List<Map<String, Object>> blocks = new java.util.ArrayList<>();
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                blocks = mapper.readValue(tpl.getContentJson(), List.class);
            } catch (Exception e) {
                // 忽略解析错误
            }
            blockService.createBlocksForPage(page.getId(), blocks, tenantId, createdBy);
        }
        return page;
    }
}