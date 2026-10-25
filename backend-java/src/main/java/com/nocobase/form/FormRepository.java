package com.nocobase.form;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormRepository extends JpaRepository<FormEntity, UUID> {
    List<FormEntity> findByCollectionNameAndTenantId(String collectionName, String tenantId);
    List<FormEntity> findByTenantId(String tenantId);
    Optional<FormEntity> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * PHASE10: 按分享 token 查表单（免登录公开填写入口用）。
     *
     * <p>注意：此处不带 tenantId 过滤 —— token 本身即凭证（全局唯一随机值），
     * 查询结果再由调用方校验有效性。无效/已撤销一律按 404 处理。
     */
    Optional<FormEntity> findByShareToken(String shareToken);
}
