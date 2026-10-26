package com.nocobase.comment;

import com.nocobase.acl.RowAclService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.comment.dto.CommentCreateRequest;
import com.nocobase.comment.dto.CommentView;
import com.nocobase.im.parser.RichTextParser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class RecordCommentService {

    private final RecordCommentRepository repository;
    private final AclEnforcer aclEnforcer;
    private final AuditService auditService;
    private final RowAclService rowAclService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.nocobase.auth.UserRepository userRepository;

    public RecordCommentService(RecordCommentRepository repository,
                                AclEnforcer aclEnforcer,
                                AuditService auditService,
                                RowAclService rowAclService,
                                ApplicationEventPublisher eventPublisher,
                                com.nocobase.auth.UserRepository userRepository) {
        this.repository = repository;
        this.aclEnforcer = aclEnforcer;
        this.auditService = auditService;
        this.rowAclService = rowAclService;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
    }

    /** 创建评论。 */
    public CommentView create(AuthenticatedUser user,
                              String collectionName,
                              String recordId,
                              CommentCreateRequest req) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), collectionName,
                AclPolicyEntity.Action.READ);

        validateContent(req.content());

        RecordCommentEntity entity = new RecordCommentEntity();
        entity.setId(UUID.randomUUID());
        entity.setCollectionName(collectionName);
        entity.setRecordId(recordId);
        entity.setParentId(req.parentId());
        entity.setAuthorId(user.userId());
        entity.setContent(req.content());
        entity.setContentType(req.contentType() != null ? req.contentType() : "text");
        entity.setCreatedAt(Instant.now());
        entity.setTenantId(user.tenantId());
        setMentionsJson(entity, req.content());

        repository.save(entity);

        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "record.comment.create", "record_comment", entity.getId().toString(),
                Map.of("collection", collectionName, "record_id", recordId,
                        "content_length", req.content().length()));

        publishMentionEvents(entity, req.content());

        return toView(entity, 0);
    }

    /** 编辑评论（仅作者）。 */
    public CommentView update(AuthenticatedUser user,
                              String collectionName,
                              String recordId,
                              UUID commentId,
                              CommentCreateRequest req) {
        RecordCommentEntity existing = findOrNotFound(commentId, collectionName, recordId);
        assertAuthor(existing, user);

        validateContent(req.content());
        existing.setContent(req.content());
        existing.setEditedAt(Instant.now());
        setMentionsJson(existing, req.content());
        repository.save(existing);

        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "record.comment.update", "record_comment", commentId.toString(),
                Map.of("collection", collectionName, "record_id", recordId));

        publishMentionEvents(existing, req.content());

        return toView(existing, 0);
    }

    /** 软删除评论（仅作者）。 */
    public void delete(AuthenticatedUser user,
                       String collectionName,
                       String recordId,
                       UUID commentId) {
        RecordCommentEntity existing = findOrNotFound(commentId, collectionName, recordId);
        assertAuthor(existing, user);

        existing.setDeletedAt(Instant.now());
        repository.save(existing);

        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "record.comment.delete", "record_comment", commentId.toString(),
                existing);
    }

    /**
     * 顶层评论列表（游标分页，含子回复树）；同时返回总数。
     */
    @Transactional(readOnly = true)
    public List<CommentView> list(AuthenticatedUser user,
                                  String collectionName,
                                  String recordId,
                                  UUID beforeId,
                                  int limit) {
        aclEnforcer.assertCan(user.userId(), user.tenantId(), collectionName,
                AclPolicyEntity.Action.READ);

        Instant cursor = beforeId != null ? findCursor(beforeId, collectionName, recordId, user.tenantId()) : null;

        // 组装顶层评论（parentId IS NULL）
        var pageable = org.springframework.data.domain.PageRequest.of(0, limit,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));

        List<RecordCommentEntity> parents;
        if (cursor != null) {
            parents = repository.findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
                    collectionName, recordId, user.tenantId(), cursor, pageable);
        } else {
            parents = repository.findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(
                    collectionName, recordId, user.tenantId(), pageable);
        }

        // 批量装配子回复（防 N+1）
        return parents.stream()
                .map(parent -> toViewWithReplies(parent, collectionName, recordId, user.tenantId()))
                .collect(Collectors.toList());
    }

    /** 评论总数（不含已删除）。 */
    @Transactional(readOnly = true)
    public long count(String tenantId, String collectionName, String recordId) {
        return repository.countByCollectionNameAndRecordIdAndTenantIdAndDeletedAtIsNull(
                collectionName, recordId, tenantId);
    }

    // ==================== 私有工具方法 ====================

    private void validateContent(String content) {
        if (content == null || content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论内容不能为空");
        }
        if (content.length() > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论内容过长（最大 10000 字符）");
        }
    }

    private void setMentionsJson(RecordCommentEntity entity, String content) {
        RichTextParser.ParseResult parsed = RichTextParser.parse(content);
        if (!parsed.mentions().isEmpty()) {
            try {
                entity.setMentions(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(parsed.mentions().stream()
                                .map(m -> Map.of("displayName", m.displayName(),
                                        "userId", m.userId()))
                                .collect(Collectors.toList())));
            } catch (Exception e) {
                entity.setMentions("[]");
            }
        }
    }

    private void publishMentionEvents(RecordCommentEntity entity, String content) {
        RichTextParser.ParseResult parsed = RichTextParser.parse(content);
        if (!parsed.mentions().isEmpty()) {
            eventPublisher.publishEvent(new CommentMentionedEvent(this, entity, parsed.mentions()));
        }
    }

    private RecordCommentEntity findOrNotFound(UUID commentId,
                                               String collectionName,
                                               String recordId) {
        return repository.findById(commentId)
                .filter(e -> collectionName.equals(e.getCollectionName())
                        && recordId.equals(e.getRecordId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"));
    }

    private void assertAuthor(RecordCommentEntity existing, AuthenticatedUser user) {
        if (!existing.getAuthorId().equals(user.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作他人评论");
        }
    }

    /**
     * 读取 cursor 评论，取其 createdAt 作为分页游标。
     */
    private Instant findCursor(UUID beforeId, String collectionName, String recordId, String tenantId) {
        RecordCommentEntity before = repository.findById(beforeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "游标无效"));
        if (!collectionName.equals(before.getCollectionName())
                || !recordId.equals(before.getRecordId())
                || !tenantId.equals(before.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "游标不属于该记录");
        }
        return before.getCreatedAt();
    }

    /**
     * 转换为视图并批量组装子回复。
     */
    private CommentView toViewWithReplies(RecordCommentEntity parent,
                                           String collectionName,
                                           String recordId,
                                           String tenantId) {
        List<CommentView> replies = buildReplies(parent.getId(), tenantId);
        return toView(parent, replies.size());
    }

    private List<CommentView> buildReplies(UUID parentId, String tenantId) {
        List<RecordCommentEntity> children =
                repository.findByParentIdAndTenantIdOrderByCreatedAtAsc(parentId, tenantId);
        return children.stream()
                .map(c -> toView(c, 0))
                .collect(Collectors.toList());
    }

    private CommentView toView(RecordCommentEntity e, int replyCount) {
        String authorName = resolveAuthorName(e.getAuthorId());
        return new CommentView(
                e.getId(),
                e.getCollectionName(),
                e.getRecordId(),
                e.getParentId(),
                e.getAuthorId(),
                authorName,
                e.getContent(),
                e.getContentType(),
                e.getMentionsParsed(),
                e.getAttachmentId(),
                e.getEditedAt(),
                e.getDeletedAt(),
                e.getCreatedAt(),
                List.of(),
                replyCount
        );
    }

    /**
     * 获取作者名称（从用户表查询 displayName）。
     */
    private String resolveAuthorName(UUID authorId) {
        return userRepository.findById(authorId).map(com.nocobase.auth.UserEntity::getDisplayName).orElse("用户");
    }
}
