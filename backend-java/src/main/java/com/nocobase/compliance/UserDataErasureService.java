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

    public UserDataErasureService(AuditLogRepository auditLogRepository,
                                    AuditService auditService,
                                    AiConversationEntityRepository aiConversationRepo) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.aiConversationRepo = aiConversationRepo;
    }

    /**
     * 导出用户数据（不含凭据）
     */
    @Transactional(readOnly = true)
    public UserDataExport exportUserData(String userId, String tenantId) {
        UserDataExport export = new UserDataExport();
        export.setUserId(userId);
        export.setTenantId(tenantId);
        export.setExportedAt(new Date().toInstant());
        export.setExportedBy("system");

        // TODO: 填充各模块数据
        // 当前版本：占位符，返回空结构

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
        // TODO: 通过 ImMessageRepository 实现
        result.incrementMessagesErased(0);
    }

    private void eraseAiConversations(String userId, String anonymousId, UserDataErasureRequest result) {
        // TODO: 通过 AiConversationEntityRepository 实现
        result.incrementConversationsErased(0);
    }

    private void anonymizeAuditLogs(String userId, String anonymousId, UserDataErasureRequest result) {
        // 只替换用户名，不删除日志
        // TODO: 通过 AuditLogRepository 实现
        result.incrementAuditLogsAnonymized(0);
    }
}