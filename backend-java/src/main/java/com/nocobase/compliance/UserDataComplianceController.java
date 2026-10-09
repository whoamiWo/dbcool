package com.nocobase.compliance;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.audit.AuditService;
import com.nocobase.userdata.UserDataErasureRequest;
import com.nocobase.userdata.UserDataExport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户数据导入/导出/删除 API (PHASE93 🔒-4 合规).
 *
 * <p>接口:
 * <ul>
 *   <li>GET  /api/admin/users/{userId}/data-export — 导出用户数据（管理员）</li>
 *   <li>DELETE /api/admin/users/{userId}/data-erasure — 删除用户数据（管理员）</li>
 *   <li>GET  /api/users/me/data-export — 本人自助导出</li>
 *   <li>DELETE /api/users/me/data-erasure — 本人请求删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "UserDataCompliance", description = "用户数据合规接口")
public class UserDataComplianceController {

    private final UserDataErasureService service;
    private final AuditService auditService;

    public UserDataComplianceController(UserDataErasureService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /**
     * 管理员导出指定用户数据
     * 只返回结构化 JSON，不包含密码哈希、token 等凭据
     */
    @Operation(summary = "导出用户数据（管理员）")
    @GetMapping("/admin/users/{userId}/data-export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> exportUserDataForAdmin(
            @PathVariable String userId,
            @AuthenticationPrincipal AuthenticatedUser adminUser) {

        auditService.log(adminUser.tenantId(), adminUser.userId().toString(), adminUser.username(),
            "user.data.export", "UserDataExport", userId,
            Map.of("action", "admin_export", "userId", userId));

        // exportedBy 传真实操作者：留痕要能回答"谁导出的"，不能恒记 system
        UserDataExport export = service.exportUserData(userId, adminUser.tenantId(),
                adminUser.userId().toString());
        return ResponseEntity.ok(Map.of(
            "code", 0,
            "message", "success",
            "data", export
        ));
    }

    /**
     * 管理员触发用户数据删除
     */
    @Operation(summary = "删除用户数据（管理员）")
    @DeleteMapping("/admin/users/{userId}/data-erasure")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> eraseUserDataForAdmin(
            @PathVariable String userId,
            @AuthenticationPrincipal AuthenticatedUser adminUser) {

        auditService.log(adminUser.tenantId(), adminUser.userId().toString(), adminUser.username(),
            "user.data.erasure", "UserDataErasure", userId,
            Map.of("action", "admin_erasure", "userId", userId));

        UserDataErasureRequest result = service.erasesUserData(userId,
            adminUser.userId().toString(), adminUser.tenantId());
        return ResponseEntity.ok(Map.of(
            "code", 0,
            "message", "success",
            "data", result
        ));
    }

    /**
     * 本人自助导出数据
     */
    @Operation(summary = "本人数据导出")
    @GetMapping("/users/me/data-export")
    public ResponseEntity<Map<String, Object>> exportMyData(@AuthenticationPrincipal AuthenticatedUser user) {
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
            "user.data.export", "UserDataExport", user.userId().toString(),
            Map.of("source", "self-service"));

        UserDataExport export = service.exportUserData(user.userId().toString(),
                user.tenantId(), user.userId().toString());
        return ResponseEntity.ok(Map.of(
            "code", 0,
            "message", "success",
            "data", export
        ));
    }

    /**
     * 本人请求删除数据
     * 删除后返回确认信息
     */
    @Operation(summary = "请求删除个人数据")
    @DeleteMapping("/users/me/data-erasure")
    public ResponseEntity<Map<String, Object>> requestErasureMyData(@AuthenticationPrincipal AuthenticatedUser user) {
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
            "user.data.erasure", "UserDataErasure", user.userId().toString(),
            Map.of("source", "self-service"));

        UserDataErasureRequest result = service.erasesUserData(
            user.userId().toString(),
            user.userId().toString(),
            user.tenantId());
        return ResponseEntity.ok(Map.of(
            "code", 0,
            "message", "data deletion requested",
            "data", Map.of(
                "anonymized", result.isSuccess(),
                "messagesErased", result.getMessagesErased(),
                "conversationsErased", result.getConversationsErased()
            )
        ));
    }
}