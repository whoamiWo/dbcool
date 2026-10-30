package com.nocobase.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
    List<WorkflowEntity> findByCollectionNameAndTenantIdOrderByCreatedAtDesc(String collectionName, String tenantId);
    List<WorkflowEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<WorkflowEntity> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * Wave4 待办池 #6a：按 webhook token 查找已启用的工作流。
     * token 存于 trigger_json（jsonb）的 "token" 字段；表名 workflows（实体映射）。
     * 多租户 schema 由 SchemaTenantConnectionProvider 设置 search_path，native 查询落在正确 schema。
     */
    @Query(value = "select * from workflows where trigger_json::jsonb ->> 'token' = :token and enabled = true",
            nativeQuery = true)
    List<WorkflowEntity> findByWebhookToken(@Param("token") String token);
}
