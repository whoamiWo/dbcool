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
        return listRecords(collectionName, tenantId, limit, null, null);
    }

    /**
     * 列出记录(Week 17: 服务端 filter + sort).
     *
     * @param sortExpr `"name,-salary"` 逗号分隔字段,`-` 前缀 DESC(白名单由 CollectionField 决定)
     * @param filters  过滤规则镜像前端 FilterRule:[{field, op, value}](Java 端镜像前端 applyFilters 逻辑)
     */
    public List<Map<String, Object>> listRecords(String collectionName, String tenantId,
                                                int limit, String sortExpr,
                                                List<FilterRule> filters) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        List<FieldDef> fields = parseFields(meta);
        List<String> jsonRecords = tableManager.listRecords(collectionName, limit, sortExpr, fields);
        List<Map<String, Object>> records = jsonRecords.stream()
                .map(json -> {
                    try {
                        Map<String, Object> wrapper = objectMapper.readValue(
                                json,
                                new TypeReference<Map<String, Object>>() {});
                        Object inner = wrapper.get("data");
                        if (inner instanceof Map<?, ?> m) {
                            return (Map<String, Object>) m;
                        }
                        return wrapper;
                    } catch (Exception e) {
                        return Map.<String, Object>of("_raw", json);
                    }
                })
                .toList();
        // Java 端 filter(Week 17):与前端 applyFilters 镜像
        if (filters != null && !filters.isEmpty()) {
            records = records.stream()
                    .filter(r -> filters.stream().allMatch(f -> matchFilter(r.get(f.field), f)))
                    .toList();
        }
        return records;
    }

    /** Week 17: 与前端 FilterRule op 完全镜像 */
    boolean matchFilter(Object value, FilterRule rule) {
        if (rule == null || rule.op == null) return true;
        return switch (rule.op) {
            case "eq"       -> value != null && String.valueOf(value).equals(String.valueOf(rule.value));
            case "neq"      -> value != null && !String.valueOf(value).equals(String.valueOf(rule.value));
            case "contains" -> value != null && String.valueOf(value).contains(String.valueOf(rule.value == null ? "" : rule.value));
            case "gt"       -> toDouble(value) > toDouble(rule.value);
            case "lt"       -> toDouble(value) < toDouble(rule.value);
            case "empty"    -> value == null || String.valueOf(value).isEmpty();
            case "notEmpty" -> value != null && !String.valueOf(value).isEmpty();
            default         -> true; // 未知 op 视为通过
        };
    }

    static double toDouble(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return Double.NaN; }
    }

    /**
     * Week 17: 过滤规则 record(与前端 FilterRule 镜像).
     */
    public record FilterRule(String field, String op, Object value) {}

    // ============================================================
    //  Week 14.5 P3-3 补完:单条 get / update / delete
    // ============================================================

    public Map<String, Object> getRecord(String collectionName, String id, String tenantId) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        String json = tableManager.getRecord(collectionName, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        try {
            // DB 里存的是 {"data": {actual fields}};解一层
            Map<String, Object> wrapper = objectMapper.readValue(json, new TypeReference<>() {});
            Object inner = wrapper.get("data");
            if (inner instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return wrapper;
        } catch (Exception e) {
            throw new RuntimeException("record 解析失败", e);
        }
    }

    public boolean updateRecord(String collectionName, String id,
                                 Map<String, Object> data, String tenantId) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        try {
            // merge:把新 fields 合并到现有 record,避免破坏 created_by 等
            Map<String, Object> existing = getRecord(collectionName, id, tenantId);
            Map<String, Object> merged = new java.util.HashMap<>(existing);
            merged.putAll(data);
            String json = objectMapper.writeValueAsString(merged);
            return tableManager.updateRecord(collectionName, id, json) > 0;
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new RuntimeException("record 序列化失败", e);
        }
    }

    public boolean deleteRecord(String collectionName, String id, String tenantId) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        return tableManager.deleteRecord(collectionName, id) > 0;
    }
}
