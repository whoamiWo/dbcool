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

    public FormService(FormRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
}
