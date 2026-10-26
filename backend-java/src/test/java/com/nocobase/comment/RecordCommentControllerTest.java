package com.nocobase.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.comment.dto.CommentCreateRequest;
import com.nocobase.comment.dto.CommentView;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** 评论控制器测试：覆盖 REST 契约与参数夹取。 */
class RecordCommentControllerTest {

    private RecordCommentService service;
    private RecordCommentController controller;

    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        service = mock(RecordCommentService.class);
        controller = new RecordCommentController(service);
    }

    @Test
    void list_returnsCursorPage() {
        when(service.list(eq(user), eq("users"), eq("123"), any(), anyInt()))
                .thenReturn(List.of(commentView()));
        when(service.count(eq("tenant_default"), eq("users"), eq("123"))).thenReturn(1L);

        Map<String, Object> resp = controller.list("users", "123", null, 20, user);

        assertThat(resp.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CommentView> comments = (List<CommentView>) data.get("comments");
        assertThat(comments).hasSize(1);
    }

    @Test
    void list_clampsExcessiveLimit() {
        when(service.list(any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(service.count(anyString(), anyString(), anyString())).thenReturn(0L);

        controller.list("users", "123", null, 9999, user);

        // limit 必须被夹到 50，避免深翻页拖垮 DB
        verify(service).list(any(), anyString(), anyString(), any(), eq(50));
    }

    @Test
    void list_withValidBeforeId_delegatesToService() {
        UUID beforeId = UUID.randomUUID();
        when(service.list(any(), anyString(), anyString(), eq(beforeId), anyInt()))
                .thenReturn(Collections.emptyList());
        when(service.count(anyString(), anyString(), anyString())).thenReturn(0L);

        controller.list("users", "123", beforeId, 20, user);

        verify(service).list(user, "users", "123", beforeId, 20);
    }

    @Test
    void create_returnsCreated() {
        when(service.create(eq(user), eq("users"), eq("123"), any(CommentCreateRequest.class)))
                .thenReturn(commentView());

        ResponseEntity<Map<String, Object>> resp = controller.create(
                "users", "123", new CommentCreateRequest("Hello world", null, null, null), user);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void update_returnsUpdated() {
        UUID commentId = UUID.randomUUID();
        when(service.update(any(), eq("users"), eq("123"), eq(commentId), any(CommentCreateRequest.class)))
                .thenReturn(commentView());

        Map<String, Object> resp = controller.update("users", "123", commentId,
                new CommentCreateRequest("Updated", null, null, null), user);

        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void delete_success() {
        UUID commentId = UUID.randomUUID();
        doNothing().when(service).delete(any(), eq("users"), eq("123"), eq(commentId));

        Map<String, Object> resp = controller.delete("users", "123", commentId, user);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("deleted");
    }

    @Test
    void delete_notFound_propagates() {
        UUID commentId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "评论不存在"))
                .when(service).delete(any(), anyString(), anyString(), eq(commentId));

        assertThatThrownBy(() -> controller.delete("users", "123", commentId, user))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("评论不存在");
    }

    @Test
    void update_forbidden_whenNotAuthor_propagates() {
        UUID commentId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权编辑他人评论"))
                .when(service).update(any(), anyString(), anyString(), eq(commentId), any());

        assertThatThrownBy(() -> controller.update("users", "123", commentId,
                new CommentCreateRequest("Try edit", null, null, null), user))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无权");
    }

    // Helper methods
    private CommentView commentView() {
        return new CommentView(
                UUID.randomUUID(), "users", "123", null, userId, "alice",
                "Hello", "text", null, null, null, null, Instant.now(),
                Collections.emptyList(), 0
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T mock(Class<T> clazz) {
        return (T) org.mockito.Mockito.mock(clazz);
    }
}
