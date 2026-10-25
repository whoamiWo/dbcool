package com.nocobase.form;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Form 服务 — Epic 2 表单设计器.
 *
 * <p>Week 8 MVP:
 * - 创建表单(绑定 collection + layout + rules)
 * - 修改表单
 * - 删除表单
 * - 列出 collection 的所有表单
 *
 * <p>运行时校验(US-103/104)在 Week 8 简化,只在前端做;
 * 后端 Week 9+ 加服务端校验.
 */
@Service
public class FormService {

    private final FormRepository repository;
    private final ObjectMapper objectMapper;
    /** PHASE10: 公开提交落库用（匿名链路）。 */
    private final com.nocobase.meta.CollectionService collectionService;

    public FormService(FormRepository repository, ObjectMapper objectMapper,
                       com.nocobase.meta.CollectionService collectionService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.collectionService = collectionService;
    }

    @Transactional
    public FormEntity create(
            String collectionName, String title, String description,
            String layoutJson, String rulesJson,
            String tenantId, UUID createdBy
    ) {
        FormEntity form = new FormEntity();
        form.setId(UUID.randomUUID());
        form.setCollectionName(collectionName);
        form.setTitle(title);
        form.setDescription(description);
        form.setLayoutJson(layoutJson == null || layoutJson.isBlank() ? "[]" : layoutJson);
        form.setRulesJson(rulesJson == null || rulesJson.isBlank() ? "{}" : rulesJson);
        form.setTenantId(tenantId);
        form.setCreatedAt(Instant.now());
        form.setCreatedBy(createdBy);
        return repository.save(form);
    }

    @Transactional
    public FormEntity update(
            UUID id, String title, String description,
            String layoutJson, String rulesJson, String tenantId
    ) {
        FormEntity form = get(id, tenantId);
        if (title != null) form.setTitle(title);
        if (description != null) form.setDescription(description);
        if (layoutJson != null) form.setLayoutJson(layoutJson);
        if (rulesJson != null) form.setRulesJson(rulesJson);
        form.setUpdatedAt(Instant.now());
        return repository.save(form);
    }

    public FormEntity get(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form 不存在"));
    }

    public List<FormEntity> listByCollection(String collectionName, String tenantId) {
        return repository.findByCollectionNameAndTenantId(collectionName, tenantId);
    }

    public List<FormEntity> listAll(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        FormEntity form = get(id, tenantId);
        repository.delete(form);
    }

    /**
     * 解析 layout JSON 为 List<Map>.
     */
    public List<Map<String, Object>> parseLayout(FormEntity form) {
        try {
            return objectMapper.readValue(form.getLayoutJson(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("layout 解析失败", e);
        }
    }

    /**
     * 解析 rules JSON 为 Map.
     */
    public Map<String, Object> parseRules(FormEntity form) {
        try {
            return objectMapper.readValue(form.getRulesJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("rules 解析失败", e);
        }
    }

    // ============================================================
    //  PHASE10: 公开表单分享（免登录填写）
    //  设计复用 WikiPageService.share/unshare 的既有模式：
    //    token = UUID 前 32 位；置 null 即撤销
    // ============================================================

    /**
     * 生成（或重新生成）公开填写分享 token。
     *
     * @param regenerate true 时强制重新生成（用于链接泄露后轮换）
     */
    @Transactional
    public FormEntity share(UUID id, String tenantId, boolean regenerate) {
        FormEntity form = get(id, tenantId);
        if (regenerate || form.getShareToken() == null || form.getShareToken().isBlank()) {
            form.setShareToken(UUID.randomUUID().toString().substring(0, 32));
        }
        return repository.save(form);
    }

    /** 撤销公开分享（token 置 null，原链接立即失效）。 */
    @Transactional
    public FormEntity unshare(UUID id, String tenantId) {
        FormEntity form = get(id, tenantId);
        form.setShareToken(null);
        return repository.save(form);
    }

    /**
     * 按分享 token 取表单（免登录入口）。
     *
     * <p>安全约定：token 无效或已撤销一律抛 404（而非 403），不泄露表单是否存在。
     */
    @Transactional(readOnly = true)
    public FormEntity getByShareToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "表单不存在或链接已失效");
        }
        return repository.findByShareToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "表单不存在或链接已失效"));
    }

    /**
     * 匿名提交：数据落入表单绑定的 collection。
     *
     * <p>租户取自表单自身的 tenantId —— 外部请求无 JWT，必须在调用
     * CollectionService 前把 TenantContext 设为表单所属租户，
     * 否则 insertRecord 内部的 get(name) 会用空上下文导致 403。
     *
     * @return 新建记录 id
     */
    @Transactional
    public UUID submitPublic(String token, Map<String, Object> data) {
        FormEntity form = getByShareToken(token);
        if (data == null || data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交数据为空");
        }
        // 注意：TenantContext.currentTenantId() 未设置时返回**默认租户**而非 null，
        // 无法据此判断"提交前是否真的设置过"。公开提交属匿名入口（请求不经过
        // JwtAuthFilter），用完后必须 clear()，否则线程复用会把表单租户
        // 泄漏给后续请求（与 JwtAuthFilter 在 finally 中 clear() 同一原则）。
        com.nocobase.tenant.TenantContext.set(form.getTenantId());
        try {
            return collectionService.insertRecord(form.getCollectionName(), data, form.getTenantId());
        } finally {
            com.nocobase.tenant.TenantContext.clear();
        }
    }
}
