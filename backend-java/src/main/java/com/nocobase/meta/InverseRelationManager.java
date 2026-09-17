package com.nocobase.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 关联反向管理(Week 42 D2.2 — 反向关系自动化).
 *
 * <p>创建 {@code belongsTo} 时自动在 target collection 加反向 {@code hasMany} 字段;
 * 删除 belongsTo 时反向字段一并清理。
 *
 * <h3>命名约定</h3>
 * <ul>
 *   <li>反向字段名 = {@code <source>_<field>} (e.g. source=posts,field=author
 *       → 反向字段 {@code post_author} 在 users 表)</li>
 *   <li>也支持源表单一集合命名 {@code <source>_<field>_list},但本实现采用简化版</li>
 * </ul>
 *
 * <h3>反向字段的 options 标记</h3>
 * <pre>
 * {
 *   "type": "hasMany",
 *   "options": {
 *     "target": "posts",          // 反向指向的 collection
 *     "_inverseOf": "author",     // 源 belongsTo 字段名
 *     "_inverseSource": "users",  // 源 collection 名
 *     "_autoManaged": true        // 标记系统自动管理,前端 UI 隐藏删除按钮
 *   }
 * }
 * </pre>
 *
 * <h3>幂等性</h3>
 * <p>若 target collection 已存在同名反向字段,跳过添加(避免重复);
 * 删除源 belongsTo 时根据 _inverseOf 精确匹配定位。
 *
 * <h3>跨租户保护</h3>
 * <p>只操作同租户的 target collection;不同租户的 collection 不允许反向关联。
 *
 * @see CollectionService#addField
 * @see CollectionService#removeField
 */
@Component
public class InverseRelationManager {

    private static final Logger log = LoggerFactory.getLogger(InverseRelationManager.class);

    /** options 里标记反向字段的 key。 */
    public static final String KEY_INVERSE_OF = "_inverseOf";
    public static final String KEY_INVERSE_SOURCE = "_inverseSource";
    public static final String KEY_AUTO_MANAGED = "_autoManaged";

    private final CollectionRepository repository;
    private final ObjectMapper objectMapper;

    public InverseRelationManager(CollectionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 字段被加到 source collection 后,自动加反向字段到 target collection(若适用).
     *
     * <p>在事务内调用 — 与 addField 同一事务保证一致。
     *
     * @return 新加的反向字段名;非 belongsTo 或出错返回 null
     */
    public String handleAddedField(CollectionMetaEntity sourceMeta, FieldDef addedField) {
        if (!"belongsTo".equals(addedField.type())) {
            return null;
        }
        String targetName = targetCollection(addedField);
        if (targetName == null) {
            log.warn("[inverse] belongsTo '{}' 缺少 target collection,跳过反向关系", addedField.name());
            return null;
        }
        if (targetName.equals(sourceMeta.getName())) {
            log.warn("[inverse] 自引用 belongsTo '{}' 跳过反向(防止无限递归)", addedField.name());
            return null;
        }

        Optional<CollectionMetaEntity> targetOpt =
                repository.findByNameAndTenantId(targetName, sourceMeta.getTenantId());
        if (targetOpt.isEmpty()) {
            log.warn("[inverse] target collection '{}' 不存在或跨租户,跳过反向关系",
                    targetName);
            return null;
        }
        CollectionMetaEntity targetMeta = targetOpt.get();

        // 字段名约定: <source>_<fieldName>
        String inverseFieldName = sourceMeta.getName() + "_" + addedField.name();

        // 幂等检查 — 同名 + 同 _inverseOf 视为已存在
        List<FieldDef> targetFields = parseFields(targetMeta);
        boolean alreadyExists = targetFields.stream().anyMatch(f ->
                f.name().equals(inverseFieldName)
                && addedField.name().equals(f.options().get(KEY_INVERSE_OF))
                && sourceMeta.getName().equals(f.options().get(KEY_INVERSE_SOURCE)));
        if (alreadyExists) {
            log.info("[inverse] 反向字段 '{}.{}' 已存在,跳过添加",
                    targetName, inverseFieldName);
            return inverseFieldName;
        }

        // 构造反向 hasMany 字段
        FieldDef inverseField = new FieldDef(
                inverseFieldName,
                "hasMany",
                false,                   // required
                addedField.label(),      // 沿用源字段标签
                Map.of(
                        "target", sourceMeta.getName(),
                        "foreignKey", addedField.name(),
                        KEY_INVERSE_OF, addedField.name(),
                        KEY_INVERSE_SOURCE, sourceMeta.getName(),
                        KEY_AUTO_MANAGED, true
                ),
                false, // primaryKey
                false, // unique
                null   // defaultValue
        );

        // 追加到 target
        List<FieldDef> newTargetFields = new ArrayList<>(targetFields);
        newTargetFields.add(inverseField);
        try {
            targetMeta.setFieldsJson(objectMapper.writeValueAsString(newTargetFields));
            repository.save(targetMeta);
            log.info("[inverse] 自动加反向字段 '{}.{}' -> '{}.{}'",
                    sourceMeta.getName(), addedField.name(),
                    targetName, inverseFieldName);
            return inverseFieldName;
        } catch (Exception e) {
            throw new RuntimeException("反向字段序列化失败", e);
        }
    }

    /**
     * 字段从 source collection 删除时,清理反向字段.
     *
     * @return 是否实际清理了一个反向字段
     */
    public boolean handleRemovedField(CollectionMetaEntity sourceMeta, String fieldName) {
        Optional<CollectionMetaEntity> targetOpt =
                findInverseTarget(sourceMeta.getTenantId(), sourceMeta.getName(), fieldName);
        if (targetOpt.isEmpty()) {
            return false;
        }
        CollectionMetaEntity targetMeta = targetOpt.get();
        List<FieldDef> targetFields = parseFields(targetMeta);

        // 反向字段名约定
        String expectedInverseField = sourceMeta.getName() + "_" + fieldName;

        // 删除匹配的反向字段(必须同时匹配 _inverseOf + _inverseSource)
        List<FieldDef> newTargetFields = new ArrayList<>(targetFields);
        boolean removed = newTargetFields.removeIf(f ->
                f.name().equals(expectedInverseField)
                && fieldName.equals(String.valueOf(f.options().get(KEY_INVERSE_OF)))
                && sourceMeta.getName().equals(f.options().get(KEY_INVERSE_SOURCE)));

        if (!removed) return false;

        try {
            targetMeta.setFieldsJson(objectMapper.writeValueAsString(newTargetFields));
            repository.save(targetMeta);
            log.info("[inverse] 清理反向字段 '{}.{}'(源 '{}.{}' 被删除)",
                    targetMeta.getName(), expectedInverseField,
                    sourceMeta.getName(), fieldName);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("反向字段清理失败", e);
        }
    }

    /** 通过 (source collection, field name) 找反向字段所在的 target collection。 */
    private Optional<CollectionMetaEntity> findInverseTarget(String tenantId, String sourceName, String fieldName) {
        // 反向字段可能在任何目标 collection — 需要遍历所有 collection
        // MVP 阶段 collection 数量有限,O(N) 扫描可接受;Week 43+ 可用反向索引
        for (CollectionMetaEntity meta : repository.findByTenantId(tenantId)) {
            List<FieldDef> fields = parseFields(meta);
            boolean has = fields.stream().anyMatch(f ->
                    fieldName.equals(String.valueOf(f.options().get(KEY_INVERSE_OF)))
                    && sourceName.equals(f.options().get(KEY_INVERSE_SOURCE)));
            if (has) return Optional.of(meta);
        }
        return Optional.empty();
    }

    private String targetCollection(FieldDef f) {
        if (f.options() == null) return null;
        Object t = f.options().get("target");
        return (t instanceof String s && !s.isBlank()) ? s : null;
    }

    private List<FieldDef> parseFields(CollectionMetaEntity meta) {
        try {
            return objectMapper.readValue(meta.getFieldsJson(),
                    new TypeReference<List<FieldDef>>() {});
        } catch (Exception e) {
            throw new RuntimeException("fields 解析失败", e);
        }
    }
}
