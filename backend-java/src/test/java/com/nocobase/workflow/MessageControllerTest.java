package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * MessageController 单测(Week 31).
 * 覆盖 list(cursor + unreadOnly)/ markRead + 不存在的 message + 错的 cursor.
 */
class MessageControllerTest {

    private MessageRepository repo;
    private MessageController controller;
    private AuthenticatedUser testUser;

    @BeforeEach
    void setUp() {
        repo = mock(MessageRepository.class);
        controller = new MessageController(repo);
        when(repo.save(any(MessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MessageEntity makeMsg(boolean read, String body) {
        MessageEntity m = new MessageEntity();
        m.setId(UUID.randomUUID());
        m.setRecipient(testUser.userId());
        m.setType("workflow");
        m.setTitle("T");
        m.setBody(body);
        m.setRelatedId(UUID.randomUUID().toString());
        m.setRead(read);
        m.setTenantId("tenant_default");
        m.setCreatedAt(Instant.now());
        return m;
    }

    // ============ list ============

    @Test
    void list_allNoCursor_returnsAllLimited() {
        List<MessageEntity> msgs = List.of(makeMsg(false, "a"), makeMsg(true, "b"));
        when(repo.findByRecipientOrderByCreatedAtDesc(testUser.userId())).thenReturn(msgs);
        when(repo.countByRecipientAndRead(testUser.userId(), false)).thenReturn(1L);

        Map<String, Object> resp = controller.list(testUser, false, 20, null);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(1L, data.get("unread_count"));
        assertEquals(2, ((List<?>) data.get("messages")).size());
        // 都有 createdAt,next_cursor 是最后一条的时间戳
        assertNotNull(data.get("next_cursor"));
        assertEquals(false, data.get("has_more"));
    }

    @Test
    void list_unreadOnly_filtersByRead() {
        when(repo.findByRecipientAndReadOrderByCreatedAtDesc(testUser.userId(), false))
                .thenReturn(List.of(makeMsg(false, "a")));
        when(repo.countByRecipientAndRead(testUser.userId(), false)).thenReturn(1L);

        Map<String, Object> resp = controller.list(testUser, true, 20, null);

        assertEquals(1L, ((Map<?, ?>) resp.get("data")).get("unread_count"));
    }

    @Test
    void list_withCursor_usesCursorPagination() {
        String cursor = Instant.now().toString();
        when(repo.findByRecipientAndCreatedAtLessThanOrderByCreatedAtDesc(
                any(UUID.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(makeMsg(false, "older")));
        when(repo.countByRecipientAndRead(any(), anyBoolean())).thenReturn(0L);

        Map<String, Object> resp = controller.list(testUser, false, 20, cursor);

        assertEquals(0, resp.get("code"));
    }

    @Test
    void list_unreadOnly_withCursor_usesCursorUnread() {
        String cursor = Instant.now().toString();
        when(repo.findByRecipientAndCreatedAtLessThanAndReadOrderByCreatedAtDesc(
                any(UUID.class), any(Instant.class), anyBoolean(), any(Pageable.class)))
                .thenReturn(List.of(makeMsg(false, "older-unread")));
        when(repo.countByRecipientAndRead(any(), anyBoolean())).thenReturn(1L);

        Map<String, Object> resp = controller.list(testUser, true, 20, cursor);

        assertEquals(1L, ((Map<?, ?>) resp.get("data")).get("unread_count"));
    }

    @Test
    void list_invalidCursor_returns400() {
        Map<String, Object> resp = controller.list(testUser, false, 20, "not-an-instant");

        assertEquals(400, resp.get("code"));
        assertTrue(resp.get("message").toString().contains("ISO-8601"));
    }

    @Test
    void list_limitClampedTo100() {
        // 请求 9999,实际限制 100
        List<MessageEntity> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(makeMsg(false, "msg-" + i));
        when(repo.findByRecipientOrderByCreatedAtDesc(testUser.userId())).thenReturn(many);
        when(repo.countByRecipientAndRead(any(), anyBoolean())).thenReturn(0L);

        Map<String, Object> resp = controller.list(testUser, false, 9999, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(100, data.get("limit"));  // 限制到 100
        assertEquals(100, ((List<?>) data.get("messages")).size());
    }

    @Test
    void list_limitMinimum1() {
        List<MessageEntity> msgs = List.of(makeMsg(false, "a"));
        when(repo.findByRecipientOrderByCreatedAtDesc(testUser.userId())).thenReturn(msgs);
        when(repo.countByRecipientAndRead(any(), anyBoolean())).thenReturn(0L);

        Map<String, Object> resp = controller.list(testUser, false, 0, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(1, data.get("limit"));  // 最小 1
    }

    @Test
    void list_emptyResult_hasMoreFalse() {
        when(repo.findByRecipientOrderByCreatedAtDesc(testUser.userId())).thenReturn(List.of());
        when(repo.countByRecipientAndRead(any(), anyBoolean())).thenReturn(0L);

        Map<String, Object> resp = controller.list(testUser, false, 20, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(0, ((List<?>) data.get("messages")).size());
        assertEquals(false, data.get("has_more"));
        assertEquals("", data.get("next_cursor"));
    }

    @Test
    void list_unreadWithCursor_invalidButEmpty_returns400() {
        // 当 cursor 非法,即使 unreadOnly=true 也走 catch
        Map<String, Object> resp = controller.list(testUser, true, 20, "bad-cursor");
        assertEquals(400, resp.get("code"));
    }

    // ============ markRead ============

    @Test
    void markRead_found_succeeds() {
        MessageEntity m = makeMsg(false, "x");
        when(repo.findById(m.getId())).thenReturn(Optional.of(m));

        Map<String, Object> resp = controller.markRead(m.getId(), testUser);

        assertEquals(0, resp.get("code"));
        assertEquals(true, m.isRead());
        org.mockito.Mockito.verify(repo).save(m);
    }

    @Test
    void markRead_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        Map<String, Object> resp = controller.markRead(id, testUser);

        assertEquals(404, resp.get("code"));
    }

    @Test
    void markRead_wrongRecipient_returns404() {
        MessageEntity m = makeMsg(false, "x");
        m.setRecipient(UUID.randomUUID()); // 别人的 message
        when(repo.findById(m.getId())).thenReturn(Optional.of(m));

        Map<String, Object> resp = controller.markRead(m.getId(), testUser);

        assertEquals(404, resp.get("code"));
        assertEquals(false, m.isRead());  // 不应被标记
    }
}
