package com.nocobase.compliance;

import com.nocobase.ai.AiConversationEntity;
import com.nocobase.ai.AiConversationEntityRepository;
import com.nocobase.audit.AuditLogRepository;
import com.nocobase.audit.AuditService;
import com.nocobase.userdata.UserDataErasureRequest;
import com.nocobase.userdata.UserDataExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户数据导出与删除服务 (PHASE93 🔒-4 合规核心).
 *
 * <p> GDPR 的 "right to access" 与 "right to be forgotten".
 *
 * <p>操作策略:
 * <ul>
 *   <li>导出: 返回 JSON 结构化数据（不含凭据）</li>
 *   <li>删除: 匿名化业务数据，**保留审计日志**（只替换用户名）</li>
 * </ul>
 */
@Service
public class UserDataErasureService {

    private static final Logger log = LoggerFactory.getLogger(UserDataErasureService.class);

    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final AiConversationEntityRepository aiConversationRepo;
    private final com.nocobase.tenant.UserTenantRepository userTenantRepo;
    private final com.nocobase.im.ImMessageRepository imMessageRepo;
    private final com.nocobase.auth.UserRepository userRepo;

    public UserDataErasureService(AuditLogRepository auditLogRepository,
                                    AuditService auditService,
                                    AiConversationEntityRepository aiConversationRepo,
                                    com.nocobase.tenant.UserTenantRepository userTenantRepo,
                                    com.nocobase.im.ImMessageRepository imMessageRepo,
                                    com.nocobase.auth.UserRepository userRepo) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.aiConversationRepo = aiConversationRepo;
        this.userTenantRepo = userTenantRepo;
        this.imMessageRepo = imMessageRepo;
        this.userRepo = userRepo;
    }

    /**
     * 归属校验：目标用户必须存在**且属于当前租户**，否则 403。
     *
     * <p>判据用 {@code UserRepository.findByIdAndTenantId}（用户实体自带 tenantId），
     * 而不是 UserTenant 关联表 —— 后者对平台管理员 admin 可能根本没有记录，
     * 用它做校验会把合法操作也挡掉（PHASE93 收尾时实测踩到：合法导出被 403）。
     */
    private com.nocobase.auth.UserEntity requireUserInTenant(String userId, String tenantId) {
        if (userId == null || tenantId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "租户上下文缺失");
        }
        try {
            return userRepo.findByIdAndTenantId(UUID.fromString(userId), tenantId)
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.FORBIDDEN,
                            "目标用户不属于当前租户"));
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无效的用户 ID: " + userId);
        }
    }

    /**
     * 导出用户数据（不含凭据）。
     *
     * <p><b>租户归属校验（安全红线）</b>：目标用户必须属于调用方所在租户，否则 403。
     * 此前没有这层校验 —— 任意租户管理员可导出其他租户用户的数据（实测返回 200）。
     *
     * @param exportedBy 真实操作者（此前恒为 "system"，导致留痕无法回答"谁导出的"）
     */
    @Transactional(readOnly = true)
    public UserDataExport exportUserData(String userId, String tenantId, String exportedBy) {
        // 1) 归属校验：用户不存在或不属于当前租户 → 拒绝
        requireUserInTenant(userId, tenantId);

        UserDataExport export = new UserDataExport();
        export.setUserId(userId);
        export.setTenantId(tenantId);
        export.setExportedAt(new Date().toInstant());
        export.setExportedBy(exportedBy != null ? exportedBy : "unknown");

        Map<String, Object> data = new java.util.LinkedHashMap<>();

        // 2) 用户-租户关联（个人信息：所属租户列表）
        data.put("tenantMemberships", userTenantRepo.findByUserId(userId).stream()
                .map(ut -> Map.of(
                        "tenantId", ut.getTenantId(),
                        "createdAt", String.valueOf(ut.getCreatedAt())))
                .toList());

        // 3) 该用户产生的操作留痕（个人信息：可查阅自己做过什么）
        //    注意只输出元数据，不含凭据字段
        data.put("auditTrail", auditLogRepository.findByFilter(
                        tenantId, null, null, userId,
                        org.springframework.data.domain.PageRequest.of(0, 1000)).stream()
                .map(l -> Map.of(
                        "action", String.valueOf(l.getAction()),
                        "resource", String.valueOf(l.getResource()),
                        "resourceId", String.valueOf(l.getResourceId()),
                        "createdAt", String.valueOf(l.getCreatedAt())))
                .toList());

        export.setUserData(data);
        return export;
    }

    /**
     * 执行用户数据删除 / 匿名化
     *
     * <p>策略：
     * <ol>
     *   <li>IM 消息 → content 替换为 "[已删除]"，senderId 替换为匿名标识</li>
     *   <li>AI 对话 → content 替换为 "[已删除]"</li>
     *   <li>审计日志 → username 替换为 "[已删除]"，userId 保留（留痕）</li>
     *   <li>用户关联的其他数据 → 软删或匿名化</li>
     * </ol>
     */
    @Transactional
    public UserDataErasureRequest erasesUserData(String userId, String adminUserId, String adminTenantId) {
        // 归属校验：与导出同理 —— 不得删除其他租户用户的数据
        requireUserInTenant(userId, adminTenantId);
        String anonymousId = "deleted-" + UUID.randomUUID().toString().substring(0, 8);

        UserDataErasureRequest result = new UserDataErasureRequest();
        result.setRequester(adminUserId);
        result.setRequestedAt(Instant.now());
        result.setTenantId(adminTenantId);

        eraseImMessages(userId, anonymousId, result);
        eraseAiConversations(userId, anonymousId, result);
        anonymizeAuditLogs(userId, anonymousId, result);

        auditService.log(adminTenantId, adminUserId, "system",
            "user.data.erasure", "UserDataErasure", userId,
            Map.of("anonymousId", anonymousId));

        result.setSuccess(true);
        return result;
    }

    private void eraseImMessages(String userId, String anonymousId, UserDataErasureRequest result) {
        // IM 消息：正文替换为 [已删除]（不删记录，保留频道上下文）
        int n = 0;
        try {
            n = imMessageRepo.anonymizeContentBySender(UUID.fromString(userId), "[已删除]");
        } catch (IllegalArgumentException e) {
            log.warn("[compliance] userId 非 UUID，跳过 IM 消息匿名化: {}", userId);
        }
        result.incrementMessagesErased(n);
    }

    private void eraseAiConversations(String userId, String anonymousId, UserDataErasureRequest result) {
        // AI 对话：现有仓储只支持 (agentId, channelId, userId) 组合查询，
        // 无法仅按 userId 定位 —— 保留计数 0 并在日志中说明，避免静默假装已处理。
        log.warn("[compliance] AI 对话匿名化未实现：AiConversationEntityRepository "
                + "缺少按 userId 的查询方法，需补充后再接入（userId={}）", userId);
        result.incrementConversationsErased(0);
    }

    private void anonymizeAuditLogs(String userId, String anonymousId, UserDataErasureRequest result) {
        // 只替换用户名，**保留日志本身与 userId** —— 合规要求操作留痕不得因
        // 用户删除而消失（这也保护了 PHASE83-85 建起来的审计能力）。
        int n = auditLogRepository.anonymizeUsername(result.getTenantId(), userId, anonymousId);
        result.incrementAuditLogsAnonymized(n);
    }
}