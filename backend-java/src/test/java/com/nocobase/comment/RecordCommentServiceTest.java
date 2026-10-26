package com.nocobase.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.acl.RowAclService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.AclEnforcer;
import com.nocobase.auth.AclPolicyEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.UserRepository;
import com.nocobase.comment.dto.CommentCreateRequest;
import com.nocobase.comment.dto.CommentView;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 评论服务测试：覆盖业务逻辑、权限校验与软删除。 */
class RecordCommentServiceTest {

    private RecordCommentRepository repository;
    private AclEnforcer aclEnforcer;
    private AuditService auditService;
    private RowAclService rowAclService;
    private UserRepository userRepository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private RecordCommentService service;

    private final UUID userId = UUID.randomUUID();
    private final String tenantId = "tenant_default";
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", tenantId);

    @BeforeEach
    void setUp() {
        repository = mock(RecordCommentRepository.class);
        aclEnforcer = mock(AclEnforcer.class);
        auditService = mock(AuditService.class);
        rowAclService = mock(RowAclService.class);
        userRepository = mock(UserRepository.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new RecordCommentService(repository, aclEnforcer, auditService, rowAclService,
                eventPublisher, userRepository);
    }

    @Test
    void create_savesEntity() {
        var author = mockUser();
        when(repository.save(any(RecordCommentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(author));

        CommentCreateRequest req = new CommentCreateRequest("Hello @bob", "text", null, null);
        CommentView view = service.create(user, "users", "123", req);

        assertThat(view.content()).isEqualTo("Hello @bob");
        verify(repository).save(any(RecordCommentEntity.class));
    }

    @Test
    void create_emptyContentThrowsBadRequest() {
        CommentCreateRequest req = new CommentCreateRequest("", "text", null, null);

        assertThatThrownBy(() -> service.create(user, "users", "123", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void create_overlongContentThrowsBadRequest() {
        CommentCreateRequest req = new CommentCreateRequest("x".repeat(10001), "text", null, null);

        assertThatThrownBy(() -> service.create(user, "users", "123", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_verifiesAuthor() {
        var existing = commentEntity();
        when(repository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
        var author = mockUser();
        when(repository.save(any(RecordCommentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(author));

        CommentCreateRequest req = new CommentCreateRequest("Updated", "text", null, null);
        service.update(user, "users", "123", existing.getId(), req);

        assertThat(existing.getContent()).isEqualTo("Updated");
        assertThat(existing.getEditedAt()).isNotNull();
        verify(repository).save(existing);
    }

    @Test
    void update_nonAuthorThrowsForbidden() {
        var existing = commentEntity();
        existing.setAuthorId(UUID.randomUUID());
        when(repository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

        CommentCreateRequest req = new CommentCreateRequest("Try edit", "text", null, null);

        assertThatThrownBy(() -> service.update(user, "users", "123", existing.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void update_commentNotFound() {
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());

        CommentCreateRequest req = new CommentCreateRequest("Update", "text", null, null);

        assertThatThrownBy(() -> service.update(user, "users", "123", UUID.randomUUID(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delete_softDeletes() {
        var existing = commentEntity();
        when(repository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
        when(repository.save(any(RecordCommentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.delete(user, "users", "123", existing.getId());

        verify(repository).save(existing);
        assertThat(existing.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_nonAuthorThrowsForbidden() {
        var existing = commentEntity();
        existing.setAuthorId(UUID.randomUUID());
        when(repository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(user, "users", "123", existing.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void delete_commentNotFound() {
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(user, "users", "123", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void list_firstPage_usesNonCursorQuery() {
        var parent = commentEntity();
        when(repository
                .findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq("users"), eq("123"), eq(tenantId), any(Pageable.class)))
                .thenReturn(List.of(parent));
        var author = mockUser();
        when(repository.findByParentIdInAndTenantIdOrderByCreatedAtAsc(any(), eq(tenantId)))
                .thenReturn(Collections.emptyList());
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(author));

        List<CommentView> result = service.list(user, "users", "123", null, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(parent.getId());
    }

    @Test
    void list_withCursor_usesCursorQuery() {
        var cursorEntity = commentEntity();
        var parent = commentEntity();
        when(repository.findById(eq(cursorEntity.getId()))).thenReturn(Optional.of(cursorEntity));
        when(repository
                .findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
                        eq("users"), eq("123"), eq(tenantId), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(parent));
        var author = mockUser();
        when(repository.findByParentIdInAndTenantIdOrderByCreatedAtAsc(any(), eq(tenantId)))
                .thenReturn(Collections.emptyList());
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(author));

        List<CommentView> result = service.list(user, "users", "123", cursorEntity.getId(), 20);

        assertThat(result).hasSize(1);
        verify(repository)
                .findByCollectionNameAndRecordIdAndTenantIdAndParentIdIsNullAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
                        eq("users"), eq("123"), eq(tenantId), any(Instant.class), any(Pageable.class));
    }

    @Test
    void count_excludesDeleted() {
        when(repository.countByCollectionNameAndRecordIdAndTenantIdAndDeletedAtIsNull(
                eq("users"), eq("123"), eq(tenantId))).thenReturn(5L);

        long result = service.count(tenantId, "users", "123");

        assertThat(result).isEqualTo(5L);
    }

    @Test
    void create_enforcesReadAcl() {
        var author = mockUser();
        when(repository.save(any(RecordCommentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(eq(userId))).thenReturn(Optional.of(author));

        service.create(user, "users", "123", new CommentCreateRequest("hi", "text", null, null));

        verify(aclEnforcer).assertCan(userId, tenantId, "users", AclPolicyEntity.Action.READ);
    }

    // Helper methods
    private RecordCommentEntity commentEntity() {
        var entity = new RecordCommentEntity();
        entity.setId(UUID.randomUUID());
        entity.setCollectionName("users");
        entity.setRecordId("123");
        entity.setContent("Hello");
        entity.setContentType("text");
        entity.setAuthorId(userId);
        entity.setTenantId(tenantId);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private com.nocobase.auth.UserEntity mockUser() {
        var u = mock(com.nocobase.auth.UserEntity.class);
        when(u.getId()).thenReturn(userId);
        when(u.getUsername()).thenReturn("alice");
        when(u.getDisplayName()).thenReturn("Alice");
        when(u.getTenantId()).thenReturn(tenantId);
        return u;
    }

    @SuppressWarnings("unchecked")
    private <T> T mock(Class<T> clazz) {
        return (T) org.mockito.Mockito.mock(clazz);
    }
}
