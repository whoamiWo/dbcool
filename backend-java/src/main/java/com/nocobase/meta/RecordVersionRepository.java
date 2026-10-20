package com.nocobase.meta;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 业务记录版本仓储(PHASE125)。
 *
 * <p>所有查询均带 {@code tenantId} —— record_version 是跨租户共享的元数据表,
 * 必须靠租户列隔离(动态表本身按 schema 隔离,但版本表不是)。
 */
@Repository
public interface RecordVersionRepository extends JpaRepository<RecordVersionEntity, UUID> {

    /**
     * 某条记录的全部版本(新 → 旧)。
     */
    List<RecordVersionEntity> findByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
            String tenantId, String collectionName, UUID recordId);

    /**
     * 取该记录当前最大版本号(用于生成下一个版本号)。
     */
    Optional<RecordVersionEntity> findTopByTenantIdAndCollectionNameAndRecordIdOrderByVersionDesc(
            String tenantId, String collectionName, UUID recordId);

    /**
     * 取指定版本(用于对比 / 回滚)。
     */
    Optional<RecordVersionEntity> findByTenantIdAndCollectionNameAndRecordIdAndVersion(
            String tenantId, String collectionName, UUID recordId, Integer version);

    /**
     * 删除某条记录的全部版本(配合"彻底删除"使用)。
     */
    long deleteByTenantIdAndCollectionNameAndRecordId(
            String tenantId, String collectionName, UUID recordId);
}
