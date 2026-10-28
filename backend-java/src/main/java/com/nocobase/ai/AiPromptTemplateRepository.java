package com.nocobase.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

/**
 * AI 提示词模板仓库 (C4)。
 */
public interface AiPromptTemplateRepository extends JpaRepository<AiPromptTemplateEntity, UUID> {

    /** 根据租户筛选（全站查询）。 */
    @Query("SELECT t FROM AiPromptTemplateEntity t WHERE t.tenantId = :tenantId ORDER BY t.updatedAt DESC")
    List<AiPromptTemplateEntity> findByTenantId(@Param("tenantId") String tenantId);

    /** 根据名称模糊搜索（租户范围内）。 */
    @Query("SELECT t FROM AiPromptTemplateEntity t WHERE t.tenantId = :tenantId AND LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%')) ORDER BY t.updatedAt DESC")
    List<AiPromptTemplateEntity> searchByName(@Param("tenantId") String tenantId, @Param("name") String name);
}