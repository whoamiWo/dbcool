package com.nocobase.wiki;

import com.nocobase.auth.AclEnforcer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Wiki 权限服务 — 复用 ACL 系统，提供知识库级/文档级权限控制。
 *
 * <p>支持四种操作: CREATE / READ / UPDATE / DELETE
 */
@Service
public class WikiPermissionService {

    /** 操作类型 */
    public enum Action {
        CREATE, READ, UPDATE, DELETE
    }

    private final AclEnforcer aclEnforcer;

    public WikiPermissionService(AclEnforcer aclEnforcer) {
        this.aclEnforcer = aclEnforcer;
    }

    // ============================================================
    //  知识库权限
    // ============================================================

    /** 检查用户是否有知识库的操作权限 */
    public boolean hasKbPermission(UUID userId, String tenantId, UUID kbId, Action action) {
        if (hasNoRoles(userId, tenantId)) return true;
        return aclEnforcer.isAllowed(userId, tenantId, "knowledge_base", aclPolicyAction(action));
    }

    /** 强制检查知识库权限，无权限时抛 403 */
    public void assertKbPermission(UUID userId, String tenantId, UUID kbId, Action action) {
        if (!hasKbPermission(userId, tenantId, kbId, action)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "ACL 拒绝: 无权 " + action + " 知识库 " + kbId);
        }
    }

    // ============================================================
    //  文档权限（含父文档继承）
    // ============================================================

    /**
     * 检查用户是否有文档的操作权限。
     * <p>继承逻辑: 子文档可继承父文档的字段级权限（由 filterPageDto 处理）
     */
    public boolean hasPagePermission(UUID userId, String tenantId, UUID pageId, Action action) {
        if (hasNoRoles(userId, tenantId)) return true;
        return aclEnforcer.isAllowed(userId, tenantId, "wiki_page", aclPolicyAction(action));
    }

    /** 强制检查文档权限 */
    public void assertPagePermission(UUID userId, String tenantId, UUID pageId, Action action) {
        if (!hasPagePermission(userId, tenantId, pageId, action)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "ACL 拒绝: 无权 " + action + " 文档 " + pageId);
        }
    }

    // ============================================================
    //  字段级权限过滤
    // ============================================================

    /**
     * 过滤可写字段（根据 ACL FIELD policy）
     * @return 不可写字段集合，null 表示无限制
     */
    public Set<String> filterWritableFields(UUID userId, String tenantId, String collection, Action action) {
        return aclEnforcer.filterWritableFields(userId, tenantId, collection, aclPolicyAction(action));
    }

    /**
     * 过滤单条文档记录（隐藏不可读字段）
     */
    public Map<String, Object> filterPageRecord(UUID userId, String tenantId, Map<String, Object> record) {
        return aclEnforcer.filterRecord(userId, tenantId, "wiki_page", record);
    }

    /**
     * 过滤知识库记录
     */
    public Map<String, Object> filterKbRecord(UUID userId, String tenantId, Map<String, Object> record) {
        return aclEnforcer.filterRecord(userId, tenantId, "knowledge_base", record);
    }

    // ============================================================
    //  工具方法
    // ============================================================

    /**
     * 用户是否未分配任何角色(含继承)。
     *
     * <p>与 AclEnforcer「无 policy 配置 → 默认允许」语义对齐:无角色意味着
     * 没有任何 ACL 策略作用于该用户,因此放行(而非拒绝)。
     * 生产环境用户均带角色,仍严格按策略判定。
     */
    private boolean hasNoRoles(UUID userId, String tenantId) {
        return aclEnforcer.loadRoleIdsIncludingInheritance(userId, tenantId).isEmpty();
    }

    private com.nocobase.auth.AclPolicyEntity.Action aclPolicyAction(Action action) {
        return switch (action) {
            case CREATE -> com.nocobase.auth.AclPolicyEntity.Action.CREATE;
            case READ -> com.nocobase.auth.AclPolicyEntity.Action.READ;
            case UPDATE -> com.nocobase.auth.AclPolicyEntity.Action.UPDATE;
            case DELETE -> com.nocobase.auth.AclPolicyEntity.Action.DELETE;
        };
    }
}
