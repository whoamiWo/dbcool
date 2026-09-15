package com.nocobase.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 关联字段解析(Week 41 复核 D2)。
 *
 * <p>此前 {@code belongsTo} / {@code hasMany} 只是 UUID 列,读取时<strong>不做任何展开</strong>,
 * 表格里直接显示裸 UUID(见 {@code TableView.renderCell} 的 {@code String(value)} 兜底),
 * 且创建 {@code belongsTo} 时不会生成反向关系。
 *
 * <p>本服务把关联字段展开为 {@code {id, title}},{@code title} 取目标记录的标题字段,
 * 使前端能直接渲染可读值。
 *
 * <p><strong>标题字段选取约定</strong>:优先名为 {@code name} / {@code title} 的字段,
 * 其次第一个 text 类型字段,最后回退为 id 本身。
 *
 * <p>解析失败时保留原始值 —— 关联解析是展示增强,不应影响主流程。
 */
@Service
public class RelationResolver {

    private static final Logger log = LoggerFactory.getLogger(RelationResolver.class);

    private final CollectionRepository collectionRepository;
    private final DynamicTableManager tableManager;
    private final ObjectMapper objectMapper;

    public RelationResolver(
            CollectionRepository collectionRepository,
            DynamicTableManager tableManager,
            ObjectMapper objectMapper
    ) {
        this.collectionRepository = collectionRepository;
        this.tableManager = tableManager;
        this.objectMapper = objectMapper;
    }

    /** 就地展开 record 中的关联字段(belongsTo / hasMany)。 */
    public void expandRelations(List<FieldDef> fields, Map<String, Object> record, String tenantId) {
        if (fields == null || fields.isEmpty() || record == null || record.isEmpty()) return;
        for (FieldDef f : fields) {
            if (f == null) continue;
            String type = f.type();
            if (!"belongsTo".equals(type) && !"hasMany".equals(type)) continue;

            Object raw = record.get(f.name());
            if (raw == null) continue;
            String target = targetCollection(f);
            if (target == null) continue;

            try {
                if ("belongsTo".equals(type)) {
                    String id = String.valueOf(raw);
                    record.put(f.name(), Map.of("id", id, "title", titleOf(target, id, tenantId)));
                } else if (raw instanceof Collection<?> ids) {
                    List<Map<String, String>> expanded = new ArrayList<>();
                    for (Object o : ids) {
                        String id = String.valueOf(o);
                        expanded.add(Map.of("id", id, "title", titleOf(target, id, tenantId)));
                    }
                    record.put(f.name(), expanded);
                }
            } catch (Exception e) {
                log.warn("[relation] 展开字段 {} 失败(保留原值): {}", f.name(), e.getMessage());
            }
        }
    }

    /** 目标 collection 名:{@code options.target} 优先,回退 {@code options.collection}。 */
    private static String targetCollection(FieldDef f) {
        if (f.options() == null) return null;
        Object t = f.options().get("target") != null
                ? f.options().get("target")
                : f.options().get("collection");
        return t instanceof String s && !s.isBlank() ? s : null;
    }

    /** 读取目标记录并返回其标题字段值;任何异常都回退为 id。 */
    private String titleOf(String collectionName, String id, String tenantId) {
        try {
            Optional<CollectionMetaEntity> metaOpt =
                    collectionRepository.findByNameAndTenantId(collectionName, tenantId);
            if (metaOpt.isEmpty()) return id;

            List<FieldDef> targetFields = objectMapper.readValue(
                    metaOpt.get().getFieldsJson(), new TypeReference<List<FieldDef>>() {});
            String titleField = pickTitleField(targetFields);
            if (titleField == null) return id;

            String json = tableManager.getRecord(collectionName, id).orElse(null);
            if (json == null) return id;

            Map<String, Object> wrapper =
                    objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object inner = wrapper.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (inner instanceof Map) ? (Map<String, Object>) inner : wrapper;
            Object v = data.get(titleField);
            return v == null ? id : String.valueOf(v);
        } catch (Exception e) {
            return id;
        }
    }

    private static String pickTitleField(List<FieldDef> fields) {
        if (fields == null || fields.isEmpty()) return null;
        for (FieldDef f : fields) {
            if (f != null && ("name".equals(f.name()) || "title".equals(f.name()))) return f.name();
        }
        for (FieldDef f : fields) {
            if (f != null && "text".equals(f.type())) return f.name();
        }
        return fields.get(0) != null ? fields.get(0).name() : null;
    }
}
