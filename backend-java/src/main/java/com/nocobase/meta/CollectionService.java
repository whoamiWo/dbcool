package com.nocobase.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Collection 服务 — Week 7 扩展支持 update / addField / removeField.
 */
@Service
public class CollectionService {

    private final CollectionRepository repository;
    private final DynamicTableManager tableManager;
    private final AsyncMigrationService migrationService;
    private final ObjectMapper objectMapper;

    public CollectionService(
            CollectionRepository repository,
            DynamicTableManager tableManager,
            AsyncMigrationService migrationService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.tableManager = tableManager;
        this.migrationService = migrationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CollectionMetaEntity create(
            String name, String title, String description,
            List<FieldDef> fields, String tenantId, UUID createdBy
    ) {
        if (repository.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Collection 已存在: " + name);
        }
        tableManager.createTable(name);
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(UUID.randomUUID());
        meta.setName(name);
        meta.setTitle(title);
        meta.setDescription(description);
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields == null ? List.of() : fields));
        } catch (Exception e) {
            throw new RuntimeException("fields 序列化失败", e);
        }
        meta.setTenantId(tenantId);
        meta.setCreatedAt(Instant.now());
        meta.setCreatedBy(createdBy);
        return repository.save(meta);
    }

    public List<CollectionMetaEntity> list(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    public CollectionMetaEntity get(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Collection 不存在: " + name));
    }

    public List<FieldDef> parseFields(CollectionMetaEntity meta) {
        try {
            return objectMapper.readValue(meta.getFieldsJson(), new TypeReference<List<FieldDef>>() {});
        } catch (Exception e) {
            throw new RuntimeException("fields 解析失败", e);
        }
    }

    @Transactional
    public CollectionMetaEntity updateMeta(String name, String title, String description, String tenantId) {
        CollectionMetaEntity meta = get(name);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改");
        }
        if (title != null) meta.setTitle(title);
        if (description != null) meta.setDescription(description);
        return repository.save(meta);
    }

    /**
     * 添加字段.同步执行,失败转异步.
     *
     * @return null = 同步成功;UUID = 异步 job_id
     */
    @Transactional
    public UUID addField(String name, FieldDef field, String tenantId, UUID userId) {
        CollectionMetaEntity meta = get(name);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改");
        }
        List<FieldDef> fields = new ArrayList<>(parseFields(meta));
        if (fields.stream().anyMatch(f -> f.name().equals(field.name()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "字段已存在: " + field.name());
        }
        fields.add(field);
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException("fields 序列化失败", e);
        }
        repository.save(meta);
        try {
            migrationService.executeSync(name, MigrationJobEntity.Operation.ADD_FIELD,
                    Map.of("name", field.name(), "type", field.type()));
            return null;
        } catch (AsyncMigrationService.LockTimeoutException e) {
            return migrationService.submitAsync(name, tenantId,
                    MigrationJobEntity.Operation.ADD_FIELD,
                    Map.of("name", field.name(), "type", field.type()), userId);
        }
    }

    @Transactional
    public UUID removeField(String name, String fieldName, String tenantId, UUID userId) {
        CollectionMetaEntity meta = get(name);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改");
        }
        List<FieldDef> fields = new ArrayList<>(parseFields(meta));
        boolean removed = fields.removeIf(f -> f.name().equals(fieldName));
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字段不存在: " + fieldName);
        }
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException("fields 序列化失败", e);
        }
        repository.save(meta);
        try {
            migrationService.executeSync(name, MigrationJobEntity.Operation.DROP_FIELD,
                    Map.of("name", fieldName));
            return null;
        } catch (AsyncMigrationService.LockTimeoutException e) {
            return migrationService.submitAsync(name, tenantId,
                    MigrationJobEntity.Operation.DROP_FIELD,
                    Map.of("name", fieldName), userId);
        }
    }

    @Transactional
    public UUID renameField(String name, String oldName, String newName, String tenantId, UUID userId) {
        CollectionMetaEntity meta = get(name);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改");
        }
        if (oldName.equals(newName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新旧名称相同");
        }
        List<FieldDef> fields = new ArrayList<>(parseFields(meta));

        // 先检查 newName 是不是已经在 list 里(说明原来就有同名,不是从 oldName 改的)
        long existingCount = fields.stream().filter(f -> f.name().equals(newName)).count();
        boolean hasOldName = fields.stream().anyMatch(f -> f.name().equals(oldName));
        if (!hasOldName) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字段不存在: " + oldName);
        }
        if (existingCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标字段名已存在: " + newName);
        }

        // 替换 oldName → newName
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(oldName)) {
                FieldDef old = fields.get(i);
                fields.set(i, new FieldDef(newName, old.type(), old.required(), old.label(), old.options()));
                break;
            }
        }
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException("fields 序列化失败", e);
        }
        repository.save(meta);
        try {
            migrationService.executeSync(name, MigrationJobEntity.Operation.RENAME_FIELD,
                    Map.of("oldName", oldName, "newName", newName));
            return null;
        } catch (AsyncMigrationService.LockTimeoutException e) {
            return migrationService.submitAsync(name, tenantId,
                    MigrationJobEntity.Operation.RENAME_FIELD,
                    Map.of("oldName", oldName, "newName", newName), userId);
        }
    }

    // ============================================================
    //  Records(Week 5)
    // ============================================================

    public UUID insertRecord(String collectionName, Map<String, Object> data, String tenantId) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        UUID id = UUID.randomUUID();
        try {
            String json = objectMapper.writeValueAsString(data);
            tableManager.insertRecord(collectionName, id.toString(), json);
            return id;
        } catch (Exception e) {
            throw new RuntimeException("record 序列化失败", e);
        }
    }

    public List<Map<String, Object>> listRecords(String collectionName, String tenantId, int limit) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        List<String> jsonRecords = tableManager.listRecords(collectionName, limit);
        return jsonRecords.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(
                                json,
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        return Map.<String, Object>of("_raw", json);
                    }
                })
                .toList();
    }
}