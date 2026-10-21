package com.nocobase.compliance;

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
    private final UserDataErasureLogRepository erasureLogRepo;

    public UserDataErasureService(AuditLogRepository auditLogRepository,
                                    AuditService auditService,
                                    AiConversationEntityRepository aiConversationRepo,
                                    com.nocobase.tenant.UserTenantRepository userTenantRepo,
                                    com.nocobase.im.ImMessageRepository imMessageRepo,
                                    com.nocobase.auth.UserRepository userRepo,
                                    UserDataErasureLogRepository erasureLogRepo) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.aiConversationRepo = aiConversationRepo;
        this.userTenantRepo = userTenantRepo;
        this.imMessageRepo = imMessageRepo;
        this.userRepo = userRepo;
        this.erasureLogRepo = erasureLogRepo;
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

        // PHASE111：AI 会话匿名化改为传租户（此前传 anonymousId 且未使用）
        eraseImMessages(userId, anonymousId, result);
        eraseAiConversations(userId, adminTenantId, result);
        anonymizeAuditLogs(userId, anonymousId, result);

        auditService.log(adminTenantId, adminUserId, "system",
            "user.data.erasure", "UserDataErasure", userId,
            Map.of("anonymousId", anonymousId));

        // PHASE111：success 语义 —— 仅当三步均真正完成才置 true。
        // eraseAiConversations 若执行失败或残留未清理会抛 IllegalStateException，
        // 事务回滚且不会走到这一行；因此"失败却报成功"在语义上不可能发生（红线 2）。
        // 注意：某一步处理 0 条（用户本就没有该类数据）属正常成功，不代表失败。
        result.setSuccess(true);

        // PHASE112：删除请求台账 —— 把本次删除结果持久化到 user_data_erasure_log（V52 表），
        // 供合规审计直接检索（例如"过去 90 天该租户发起过几次 GDPR 删除"）。
        // 与 auditService.log（操作留痕）互补：台账字段窄、计数精确，是删除请求的持久凭证。
        // 同事务内写入：若后续异常回滚，台账一并回滚，不会留下"有台账但没真删"的脏记录。
        UserDataErasureLogEntity ledger = new UserDataErasureLogEntity();
        ledger.setId(UUID.randomUUID().toString());
        ledger.setUserId(userId);
        ledger.setTenantId(adminTenantId);
        ledger.setRequesterId(adminUserId);
        ledger.setAnonymousId(anonymousId);
        ledger.setMessagesErased(result.getMessagesErased());
        ledger.setConversationsErased(result.getConversationsErased());
        ledger.setAuditLogsAnonymized(result.getAuditLogsAnonymized());
        ledger.setRequestedAt(result.getRequestedAt());
        erasureLogRepo.save(ledger);
        log.info("[compliance] 删除请求台账已记录 userId={} tenant={} ledgerId={}",
                userId, adminTenantId, ledger.getId());
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

    /**
     * PHASE111：真正匿名化该用户在本租户下的 AI 会话内容（GDPR right to be forgotten）。
     *
     * <p>契约（禁止静默成功，红线 2）：
     * <ul>
     *   <li>成功：把会话 {@code messagesJson} 替换为合法 JSON 占位（保留记录行、清除内容），
     *       并把<b>真实受影响行数</b>累加到 {@code result.conversationsErased}；</li>
     *   <li>失败（仓储调用抛异常）或执行后<b>仍残留原始内容</b> → 抛 {@code IllegalStateException}，
     *       外层 {@code @Transactional} 回滚，{@code result.success} <b>不会被置 true</b>；</li>
     *   <li>用户本就没有会话（n=0 且复查无残留）属正常成功，不得仅因 0 条判定失败。</li>
     * </ul>
     *
     * <p>占位取 {@code "[]"}：{@code messages} 列是 {@code jsonb NOT NULL}，且
     * {@code AgentService#parseMessages} 期望 JSON 数组；传非 JSON 文本会导致列写入报错、
     * 或让下游解析静默降级成"看起来已删"（此前假实现正是靠"计数 0 + 报成功"蒙混）。
     */
    private void eraseAiConversations(String userId, String tenantId, UserDataErasureRequest result) {
        UUID uid;
        try {
            uid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            // requireUserInTenant 已用 UUID.fromString 校验过，此处理论不可达；真到这儿说明
            // 入参被改坏，必须失败而不是"跳过 + 报成功"。
            throw new IllegalStateException(
                    "[compliance] 无效的用户 ID，无法匿名化 AI 会话: " + userId, e);
        }

        final String placeholder = "[]";
        int n;
        try {
            n = aiConversationRepo.anonymizeMessagesByUserId(uid, tenantId, placeholder);
        } catch (RuntimeException e) {
            log.error("[compliance] AI 会话匿名化失败 userId={} tenant={}: {}",
                    userId, tenantId, e.getMessage(), e);
            throw new IllegalStateException("AI 会话匿名化失败，删除请求未生效", e);
        }

        // 残留校验：防止 UPDATE 静默未生效（条件不匹配 / 绑定异常但没抛错）
        long remaining = aiConversationRepo.findByUserIdAndTenantId(uid, tenantId).stream()
                .filter(c -> c.getMessagesJson() != null
                        && !placeholder.equals(c.getMessagesJson().trim()))
                .count();
        if (remaining > 0) {
            log.error("[compliance] AI 会话匿名化后仍有 {} 条残留 userId={} tenant={}",
                    remaining, userId, tenantId);
            throw new IllegalStateException(
                    "AI 会话匿名化未生效，仍有 " + remaining + " 条会话含原始内容");
        }

        result.incrementConversationsErased(n);
        log.info("[compliance] AI 会话匿名化完成 userId={} tenant={} count={}", userId, tenantId, n);
    }

    private void anonymizeAuditLogs(String userId, String anonymousId, UserDataErasureRequest result) {
        // 只替换用户名，**保留日志本身与 userId** —— 合规要求操作留痕不得因
        // 用户删除而消失（这也保护了 PHASE83-85 建起来的审计能力）。
        int n = auditLogRepository.anonymizeUsername(result.getTenantId(), userId, anonymousId);
        result.incrementAuditLogsAnonymized(n);
    }
}