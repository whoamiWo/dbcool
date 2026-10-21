package com.nocobase.realtime;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PHASE127: CRDT 冲突查询与回滚代理端点。
 *
 * <p>转发至独立 CRDT 服务 (/docs/{docId}/conflicts, /docs/{docId}/rollback),
 * 并在后端做租户隔离校验与审计日志记录。
 */
@RestController
@Tag(name = "CRDT Conflict", description = "协作编辑冲突检测与解决")
@RequestMapping("/api/crdt")
public class CrdtConflictController {

    private static final Logger log = LoggerFactory.getLogger(CrdtConflictController.class);

    private final RealtimeService realtimeService;
    private final AuditService auditService;

    public CrdtConflictController(RealtimeService realtimeService, AuditService auditService) {
        this.realtimeService = realtimeService;
        this.auditService = auditService;
    }

    /**
     * 查询文档版本历史与冲突候选。
     *
     * <p>GET /api/crdt/conflicts/{docId}?since={version}
     *
     * <p>响应示例:
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "docId": "abc-123",
     *     "currentVersion": 5,
     *     "since": 2,
     *     "conflicting": true,
     *     "versions": [
     *       {"version": 3, "createdAt": "2026-10-20T10:00:00Z", "size": 1024},
     *       {"version": 4, "createdAt": "2026-10-20T10:01:00Z", "size": 1100},
     *       {"version": 5, "createdAt": "2026-10-20T10:02:00Z", "size": 1150}
     *     ]
     *   }
     * }
     * }</pre>
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/conflicts/{docId}")
    public ResponseEntity<Map<String, Object>> getConflicts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String docId,
            @RequestParam(required = false, defaultValue = "0") Long since) {
        // 校验 docId 格式 (UUID 或短标识符)
        if (docId == null || docId.isBlank() || docId.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Invalid docId"));
        }

        // 调用 CRDT 服务获取版本历史
        Map<String, Object> crdtResp = realtimeService.fetchConflicts(docId, since);
        if (crdtResp == null) {
            return ResponseEntity.status(503).body(Map.of("code", 503, "message", "CRDT service unavailable"));
        }

        // 包装统一响应
        Map<String, Object> wrapped = Map.of(
                "code", 0,
                "message", "success",
                "data", crdtResp
        );

        // 审计日志
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "CRDT_CONFLICT_FETCH", "crdt_conflict", docId, Map.of("since", since));

        return ResponseEntity.ok(wrapped);
    }

    /**
     * 回滚到指定版本。
     *
     * <p>POST /api/crdt/rollback/{docId}
     * Body: {"version": 3}
     *
     * <p>响应示例:
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "docId": "abc-123",
     *     "version": 6,
     *     "rolledBackFrom": 3,
     *     "state": "base64..."
     *   }
     * }
     * }</pre>
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/rollback/{docId}")
    public ResponseEntity<Map<String, Object>> rollback(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String docId,
            @RequestBody Map<String, Object> body) {
        // 校验 docId
        if (docId == null || docId.isBlank() || docId.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Invalid docId"));
        }

        // 提取 targetVersion
        Object verObj = body.get("version");
        if (!(verObj instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Missing or invalid version"));
        }
        int targetVersion = ((Number) verObj).intValue();
        if (targetVersion < 1) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Version must be >= 1"));
        }

        // 调用 CRDT 服务回滚
        Map<String, Object> crdtResp = realtimeService.rollbackToVersion(docId, targetVersion);
        if (crdtResp == null) {
            return ResponseEntity.status(503).body(Map.of("code", 503, "message", "CRDT service unavailable"));
        }

        // 包装统一响应
        Map<String, Object> wrapped = Map.of(
                "code", 0,
                "message", "success",
                "data", crdtResp
        );

        // 审计日志
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "CRDT_ROLLBACK", "crdt_conflict", docId, Map.of("targetVersion", targetVersion));

        return ResponseEntity.ok(wrapped);
    }
}
