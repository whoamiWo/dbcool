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
 * 表单模板服务 (US-107).
 *
 * <p>模板是一份可复用的表单定义快照 (layout + rules)，支持:
 * - 从既有表单"另存为模板"
 * - 新建表单时"从模板套用"
 * - 模板的增删改查
 *
 * <p>schema_json 结构:
 * <pre>
 * {
 *   "collectionName": "customers",
 *   "layout": [...],
 *   "rules": {...}
 * }
 * </pre>
 */
@Service
public class FormTemplateService {

    private final FormTemplateRepository repository;
    private final ObjectMapper objectMapper;

    public FormTemplateService(FormTemplateRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FormTemplateEntity create(
            String title, String description, String collectionName,
            String schemaJson, String tenantId, UUID createdBy
    ) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板标题不能为空");
        }
        FormTemplateEntity entity = new FormTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setCollectionName(collectionName);
        entity.setSchemaJson(normalizeSchema(schemaJson));
        entity.setTenantId(tenantId);
        entity.setCreatedAt(Instant.now());
        entity.setCreatedBy(createdBy);
        return repository.save(entity);
    }

    @Transactional
    public FormTemplateEntity update(
            UUID id, String title, String description, String schemaJson, String tenantId
    ) {
        FormTemplateEntity entity = get(id, tenantId);
        if (title != null && !title.isBlank()) entity.setTitle(title);
        if (description != null) entity.setDescription(description);
        if (schemaJson != null) entity.setSchemaJson(normalizeSchema(schemaJson));
        entity.setUpdatedAt(Instant.now());
        return repository.save(entity);
    }

    public FormTemplateEntity get(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "表单模板不存在"));
    }

    public List<FormTemplateEntity> listAll(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public List<FormTemplateEntity> listByCollection(String collectionName, String tenantId) {
        return repository.findByCollectionNameAndTenantId(collectionName, tenantId);
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        FormTemplateEntity entity = get(id, tenantId);
        repository.delete(entity);
    }

    /** 解析 schema_json 为 Map，供前端套用。 */
    public Map<String, Object> parseSchema(FormTemplateEntity entity) {
        try {
            return objectMapper.readValue(entity.getSchemaJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "模板 schema 解析失败");
        }
    }

    /** 空/非法 schema 归一化为 {}，保证 jsonb 列始终可读。 */
    private String normalizeSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return "{}";
        }
        try {
            objectMapper.readTree(schemaJson);
            return schemaJson;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema 不是合法 JSON");
        }
    }
}