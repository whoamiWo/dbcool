package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 业务记录版本服务(PHASE125)—— 记录变更快照与字段级 diff,支持对比与回滚。
 *
 * <p>语义对齐 Wiki 版本({@code WikiPageService.createVersion}):每次 INSERT / UPDATE /
 * DELETE / RESTORE 写一条版本;版本内容为<strong>该次操作后的完整快照</strong>,
 * 并额外记录本次<strong>相对上一状态的字段级差异</strong>。
 *
 * <p><b>为什么存完整快照而不是只存 diff</b>:
 * 回滚时需要一次性把记录恢复到某个历史状态,存快照可直接覆盖写入;
 * 只存 diff 则需从最初版本逐级回放,复杂且易错。
 *
 * <p><b>失败不阻断主流程</b>:版本写入属"留痕"能力,不应因版本表异常导致业务
 * 删除/更新失败 —— 所有异常仅 warn 记录(与 BI 缓存降级策略一致)。
 */
@Service
public class RecordVersionService {

    private static final Logger log = LoggerFactory.getLogger(RecordVersionService.class);

    /** diff 字段数上限,防止超大记录把 changed_fields 撑爆。 */
    private static final int MAX_DIFF_FIELDS = 200;

    private final RecordVersionRepository repository;
    private final ObjectMapper objectMapper;

    public RecordVersionService(RecordVersionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入一条版本记录。
     *
     * @param tenantId       租户 ID(隔离)
     * @param collectionName collection 名
     * @param recordId       记录 ID(UUID 字符串)
     * @param snapshot       操作后的完整记录快照(将被序列化为 JSON)
     * @param before         操作前的记录状态(用于计算 diff;新增时为 null)
     * @param operation      INSERT / UPDATE / DELETE / RESTORE
     * @param userId         操作者
     * @return 写入的版本实体;写入失败返回 {@link Optional#empty()}
     */
    public Optional<RecordVersionEntity> record(String tenantId, String collectionName,
                                                String recordId,
                                                Map<String, Object> snapshot,
                                                Map<String, Object> before,
                                                String operation, UUID userId) {
        try {
            UUID rid = UUID.fromString(recordId);
            int nextVersion = repository
                    .findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                            tenantId, collectionName, rid)
                    .map(e -> e.getVersion() + 1)
                    .orElse(1);

            RecordVersionEntity entity = new RecordVersionEntity();
            entity.setId(UUID.randomUUID());
            entity.setTenantId(tenantId);
            entity.setCollectionName(collectionName);
            entity.setRecordId(rid);
            entity.setVersion(nextVersion);
            entity.setDataJson(objectMapper.writeValueAsString(
                    snapshot == null ? Map.of() : snapshot));
            entity.setChangedFields(computeDiff(before, snapshot));
            entity.setOperation(operation);
            entity.setChangedBy(userId);
            entity.setChangedAt(Instant.now());

            return Optional.of(repository.save(entity));
        } catch (Exception e) {
            // 留痕失败不阻断业务主流程
            log.warn("[record-version] 写版本失败 tenant={} collection={} record={} op={} — {}",
                    tenantId, collectionName, recordId, operation, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 计算字段级差异:{@code [{field, oldValue, newValue}]}。
     *
     * <p>只输出<strong>真正发生变化</strong>的字段(Objects.equals 比较);
     * 新增字段 oldValue 为 null,删除字段 newValue 为 null。
     *
     * @return diff 的 JSON 字符串;无差异时返回 {@code "[]"}
     */
    public String computeDiff(Map<String, Object> before, Map<String, Object> after) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) keys.addAll(before.keySet());
        if (after != null) keys.addAll(after.keySet());

        for (String key : keys) {
            Object oldVal = before == null ? null : before.get(key);
            Object newVal = after == null ? null : after.get(key);
            if (Objects.equals(oldVal, newVal)) continue;
            if (diffs.size() >= MAX_DIFF_FIELDS) break;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", key);
            item.put("oldValue", oldVal == null ? null : String.valueOf(oldVal));
            item.put("newValue", newVal == null ? null : String.valueOf(newVal));
            diffs.add(item);
        }

        try {
            return objectMapper.writeValueAsString(diffs);
        } catch (Exception e) {
            log.warn("[record-version] 序列化 diff 失败 — {}", e.getMessage());
            return "[]";
        }
    }

    /** 某条记录的版本列表(新 → 旧)。 */
    public List<RecordVersionEntity> listVersions(String tenantId, String collectionName, String recordId) {
        try {
            return repository.findByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
                    tenantId, collectionName, UUID.fromString(recordId));
        } catch (IllegalArgumentException e) {
            log.warn("[record-version] 非法记录 ID: {}", recordId);
            return List.of();
        }
    }

    /** 取指定版本快照(反序列化为 Map)。 */
    public Optional<Map<String, Object>> getVersionSnapshot(String tenantId, String collectionName,
                                                            String recordId, int version) {
        try {
            Optional<RecordVersionEntity> opt = repository
                    .findByTenantIdAndCollectionNameAndRecordIdAndVersion(
                            tenantId, collectionName, UUID.fromString(recordId), version);
            if (opt.isEmpty()) return Optional.empty();
            String json = opt.get().getDataJson();
            Map<String, Object> map = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return Optional.of(map);
        } catch (Exception e) {
            log.warn("[record-version] 读取版本快照失败 record={} v={} — {}", recordId, version, e.getMessage());
            return Optional.empty();
        }
    }

    /** 删除某条记录的全部版本(彻底删除时清理,避免版本表残留)。 */
    public void deleteVersions(String tenantId, String collectionName, String recordId) {
        try {
            repository.deleteByTenantIdAndCollectionNameAndRecordId(
                    tenantId, collectionName, UUID.fromString(recordId));
        } catch (Exception e) {
            log.warn("[record-version] 清理版本失败 record={} — {}", recordId, e.getMessage());
        }
    }
}
