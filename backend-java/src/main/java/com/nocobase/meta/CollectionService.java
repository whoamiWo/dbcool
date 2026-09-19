package com.nocobase.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.common.ExpressionEvaluator;
import com.nocobase.meta.formula.FormulaEngine;
import com.nocobase.meta.rollup.RollupEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Collection 服务 — Week 7 扩展支持 update / addField / removeField。
 *
 * <p>Week 42:读取时计算 formula / rollup / lookup 派生字段。
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private final CollectionRepository repository;
    private final DynamicTableManager tableManager;
    private final AsyncMigrationService migrationService;
    private final ObjectMapper objectMapper;
    private final ExpressionEvaluator expressionEvaluator;
    private final RelationResolver relationResolver;
    /** Week 42 D2.2: 自动管理反向关系(belongsTo → hasMany)。 */
    private final InverseRelationManager inverseManager;

    /** 兼容既有测试:自建无状态依赖实例。 */
    public CollectionService(
            CollectionRepository repository,
            DynamicTableManager tableManager,
            AsyncMigrationService migrationService,
            ObjectMapper objectMapper
    ) {
        this(repository, tableManager, migrationService, objectMapper,
                new ExpressionEvaluator(),
                new RelationResolver(repository, tableManager, objectMapper),
                new InverseRelationManager(repository, objectMapper));
    }

    @Autowired
    public CollectionService(
            CollectionRepository repository,
            DynamicTableManager tableManager,
            AsyncMigrationService migrationService,
            ObjectMapper objectMapper,
            ExpressionEvaluator expressionEvaluator,
            RelationResolver relationResolver,
            InverseRelationManager inverseManager
    ) {
        this.repository = repository;
        this.tableManager = tableManager;
        this.migrationService = migrationService;
        this.objectMapper = objectMapper;
        this.expressionEvaluator = expressionEvaluator;
        this.relationResolver = relationResolver;
        this.inverseManager = inverseManager;
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
        // Week 42 D2.2: 自动反向关系 — belongsTo 在 target collection 加 hasMany
        inverseManager.handleAddedField(meta, field);
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
        // Week 42 D2.2: 清理反向关系 — 删源 belongsTo 时一并清理 target 的 hasMany
        inverseManager.handleRemovedField(meta, fieldName);
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
                fields.set(i, new FieldDef(newName, old.type(), old.required(), old.label(), old.options(), old.primaryKey(), old.unique(), old.defaultValue()));
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
        // US-003:字段约束(默认值填充 + 必填/唯一/主键校验)
        Map<String, Object> effective = applyFieldConstraints(meta, data, null);
        UUID id = UUID.randomUUID();
        try {
            String json = objectMapper.writeValueAsString(effective);
            tableManager.insertRecord(collectionName, id.toString(), json);
            return id;
        } catch (Exception e) {
            throw new RuntimeException("record 序列化失败", e);
        }
    }

    /**
     * US-003:应用字段约束 —— 默认值填充、必填校验、唯一校验、主键(=必填+唯一)。
     *
     * <p>语义说明:
     * <ul>
     *   <li><b>defaultValue</b>:字段缺失或为空时自动填充</li>
     *   <li><b>required / primaryKey</b>:填充后仍为空 → 400</li>
     *   <li><b>unique / primaryKey</b>:值非 null 且库中已存在同值 → 409(遵循 SQL NULL 语义,null 不参与唯一性判定)</li>
     * </ul>
     *
     * @param excludeId 非空时排除该记录(更新场景,避免与自身冲突)
     * @return 应用默认值后的有效数据(新 Map,不改动入参)
     */
    Map<String, Object> applyFieldConstraints(CollectionMetaEntity meta,
                                              Map<String, Object> data,
                                              String excludeId) {
        List<FieldDef> fields = parseFields(meta);
        Map<String, Object> out = new java.util.HashMap<>(data == null ? Map.of() : data);

        for (FieldDef f : fields) {
            if (f == null) continue;
            Object v = out.get(f.name());
            boolean blank = (v == null) || (v instanceof String s && s.isBlank());

            // 1) 默认值填充
            if (blank && f.defaultValue() != null && !f.defaultValue().isBlank()) {
                out.put(f.name(), f.defaultValue());
                v = f.defaultValue();
                blank = false;
            }

            // 2) 必填 / 主键非空
            if ((f.required() || f.primaryKey()) && blank) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段必填: " + f.name());
            }

            // 3) 唯一 / 主键唯一性(null 不参与)
            if ((f.unique() || f.primaryKey()) && !blank) {
                if (tableManager.existsByFieldValue(meta.getName(), f.name(), String.valueOf(v), excludeId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "字段值已存在(唯一约束): " + f.name());
                }
            }
        }
        return out;
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
        // Week 41 复核 D1.3:formula 求值;D2:关联字段展开(此前显示裸 UUID)
        // Week 42:rollup/lookup 派生字段求值
        records.forEach(r -> {
            applyFormulas(fields, r);
            applyRollupAndLookup(fields, r, tenantId);
            relationResolver.expandRelations(fields, r, tenantId);
        });
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

    /**
     * Week 44:BI 聚合通道 — SQL 下推 GROUP BY(供 BiReportService 透视/图表)。
     *
     * <p>聚合由 PostgreSQL 在 {@code data_<collection>} 物理表上完成,
     * 而非把全量记录拉到 Java 内存里归并 —— 后者万级记录即明显延迟且无法利用 GIN 索引。
     * 先做租户归属校验(与 listRecords 一致),再委托 DynamicTableManager 执行下推。
     */
    public List<Map<String, Object>> aggregate(String collectionName, String tenantId,
                                               List<String> groupByFields,
                                               List<DynamicTableManager.AggSpec> aggSpecs,
                                               List<FilterRule> filters) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
        }
        return tableManager.aggregate(collectionName, groupByFields, aggSpecs, filters);
    }

    /**
     * Week 41 复核 D1.3:对 formula 字段求值。
     *
     * <p>{@code formula} 此前只有类型名、物理列映射为 TEXT —— 存进去是纯文本,
     * 不会有任何计算行为。这里在读取时按 {@code options.expression} 求值,
     * 变量环境为当前记录本身,因此表达式可直接引用同记录的其他字段
     * (如 {@code price * qty})。
     *
     * <p>求值失败时该字段置 {@code null},不阻断整条记录返回。
     */
    private void applyFormulas(List<FieldDef> fields, Map<String, Object> record) {
        if (fields == null || fields.isEmpty() || record == null || record.isEmpty()) return;
        for (FieldDef f : fields) {
            if (f == null || !"formula".equals(f.type()) || f.options() == null) continue;
            Object expr = f.options().get("expression");
            if (!(expr instanceof String s) || s.isBlank()) continue;
            try {
                // Phase 48:formula 字段改走 FormulaEngine(Airtable 方言,
                // 支持 {field} 引用与 IF/CONCAT/LEFT/... 函数,委托 Aviator 求值)
                record.put(f.name(), FormulaEngine.evaluate(s, record));
            } catch (UnsupportedOperationException e) {
                // 不可变 Map(如解析失败时的 _raw 包装),跳过
                return;
            }
        }
    }

    private void applyRollupAndLookup(List<FieldDef> fields, Map<String, Object> record, String tenantId) {
        if (fields == null || fields.isEmpty() || record == null || record.isEmpty()) return;
        for (FieldDef f : fields) {
            if (f == null || f.options() == null) continue;
            if ("rollup".equals(f.type())) {
                try {
                    String relationField = String.valueOf(f.options().get("relationField"));
                    String targetField = String.valueOf(f.options().get("targetField"));
                    String agg = String.valueOf(f.options().getOrDefault("agg", "SUM"));
                    Object relatedRaw = record.get(relationField);
                    if (relatedRaw == null) continue;
                    List<Map<String, Object>> related = resolveRelatedRecords(relationField, relatedRaw, tenantId);
                    if (related.isEmpty()) continue;
                    record.put(f.name(), RollupEngine.aggregate(related, agg, targetField));
                } catch (Exception e) {
                    log.warn("[rollup] 字段 {} 求值失败: {}", f.name(), e.getMessage());
                }
            } else if ("lookup".equals(f.type())) {
                try {
                    String relationField = String.valueOf(f.options().get("relationField"));
                    String targetField = String.valueOf(f.options().get("targetField"));
                    Object relatedRaw = record.get(relationField);
                    if (relatedRaw == null) continue;
                    List<Map<String, Object>> related = resolveRelatedRecords(relationField, relatedRaw, tenantId);
                    if (related.isEmpty()) continue;
                    record.put(f.name(), RollupEngine.lookup(related, targetField));
                } catch (Exception e) {
                    log.warn("[lookup] 字段 {} 求值失败: {}", f.name(), e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveRelatedRecords(String relationField, Object relatedRaw, String tenantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (relatedRaw instanceof Map map) {
            String id = String.valueOf(map.get("id"));
            if (id != null && !id.isBlank()) {
                result.add(Map.of("id", id, "title", map.getOrDefault("title", id)));
            }
        } else if (relatedRaw instanceof Collection list) {
            for (Object o : list) {
                if (o instanceof Map m) {
                    result.add(Map.of("id", String.valueOf(m.get("id")), "title", m.getOrDefault("title", "")));
                } else {
                    result.add(Map.of("id", String.valueOf(o), "title", String.valueOf(o)));
                }
            }
        }
        return result;
    }

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
            Map<String, Object> record = (inner instanceof Map<?, ?> m)
                    ? (Map<String, Object>) m
                    : wrapper;
            List<FieldDef> fields = parseFields(meta);
            // Week 41 复核 D1.3:formula 求值;D2:关联字段展开
            applyFormulas(fields, record);
            applyRollupAndLookup(fields, record, tenantId);
            relationResolver.expandRelations(fields, record, tenantId);
            return record;
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
            // US-003:字段约束(排除自身 id,避免与自身唯一值冲突)
            Map<String, Object> effective = applyFieldConstraints(meta, merged, id);
            String json = objectMapper.writeValueAsString(effective);
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

    /**
     * 删除 collection 元数据 + 物理表(Week 41 B3 修复).
     *
     * <p>原 delete 端点只返回 "deleted (mark only)" 假成功,物理表残留在 schema,
     * 长期积累会污染 schema 并影响 ER 图统计。
     *
     * <p>顺序与失败语义(报告 3.3 建议):
     * <ol>
     *   <li>先删元数据(主流程不可逆)</li>
     *   <li>再删物理表(失败仅记日志,不回滚元数据 — 元数据已删,残留表危害小于元数据残留)</li>
     * </ol>
     */
    @Transactional
    public boolean deleteMeta(String collectionName, String tenantId) {
        CollectionMetaEntity meta = get(collectionName);
        if (!meta.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权删除该 collection");
        }
        // 1. 删元数据(主流程)
        long deleted = repository.deleteByName(collectionName);
        if (deleted == 0) {
            // 已被并发删除 — 视为幂等成功
            return false;
        }
        // 2. 删物理表(失败仅记日志,不抛)
        try {
            tableManager.dropTable(collectionName);
        } catch (Exception e) {
            // 记录日志但不抛 — 元数据已删,残留物理表可由运维清理
            org.slf4j.LoggerFactory.getLogger(CollectionService.class)
                    .warn("collection {} 元数据已删,物理表清理失败: {}", collectionName, e.getMessage());
        }
        return true;
    }
}
