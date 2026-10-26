package com.nocobase.comment;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.comment.dto.CommentCreateRequest;
import com.nocobase.comment.dto.CommentView;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记录级评论 REST 端点（H1）。
 *
 * <p>路径挂在 collection 记录下，权限沿用记录的 READ / UPDATE 语义：
 * <ul>
 *   <li>列表 / 创建：需 collection READ 权限</li>
 *   <li>编辑 / 删除：仅评论作者本人</li>
 * </ul>
 */
@RestController
@Tag(name = "Record Comments", description = "记录级评论")
@RequestMapping("/api/collections/{name}/records/{recordId}/comments")
public class RecordCommentController {

    private final RecordCommentService service;

    public RecordCommentController(RecordCommentService service) {
        this.service = service;
    }

    /**
     * 评论列表（游标分页，父评论带子回复）。
     *
     * @param beforeId 游标：返回该评论之前（更早）的记录；null 表示从最新开始
     * @param limit    单页条数，默认 20，上限 50
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(
            @PathVariable String name,
            @PathVariable String recordId,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<CommentView> comments = service.list(
                user, name, recordId, beforeId, safeLimit);
        long total = service.count(user.tenantId(), name, recordId);
        return Map.of(
                "code", 0, "message", "success",
                "data", Map.of("comments", comments, "total", total)
        );
    }

    /** 发表评论。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String name,
            @PathVariable String recordId,
            @RequestBody CommentCreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CommentView created = service.create(user, name, recordId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "code", 0, "message", "success", "data", created
        ));
    }

    /** 编辑评论（仅作者）。 */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{commentId}")
    public Map<String, Object> update(
            @PathVariable String name,
            @PathVariable String recordId,
            @PathVariable UUID commentId,
            @RequestBody CommentCreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CommentView updated = service.update(user, name, recordId, commentId, request);
        return Map.of("code", 0, "message", "success", "data", updated);
    }

    /** 软删除评论（仅作者；保留占位以维持线程）。 */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{commentId}")
    public Map<String, Object> delete(
            @PathVariable String name,
            @PathVariable String recordId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        service.delete(user, name, recordId, commentId);
        return Map.of("code", 0, "message", "deleted", "data", Map.of());
    }
}
