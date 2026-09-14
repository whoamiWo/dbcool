package com.nocobase.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MessageController WebMvcTest(Week 25 抬红线).
 * 覆盖 /api/messages 两个端点:
 *   GET  /api/messages?unreadOnly=&limit=&before=
 *   POST /api/messages/{id}/read
 */
@WebMvcTest(MessageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MessageControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SecurityConfig securityConfig;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private MessageRepository messageRepository;

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void loginAs(UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant_default");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private MessageEntity msg(UUID recipient, String title, boolean read, Instant t) {
        MessageEntity m = new MessageEntity();
        m.setId(UUID.randomUUID());
        m.setRecipient(recipient);
        m.setTitle(title);
        m.setBody("body");
        m.setType("workflow");
        m.setRead(read);
        m.setCreatedAt(t);
        m.setTenantId("tenant_default");
        return m;
    }

    @Test
    void list_returnsAllMessages() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        Instant now = Instant.parse("2026-09-14T10:00:00Z");
        when(messageRepository.findByRecipientOrderByCreatedAtDesc(uid))
                .thenReturn(List.of(msg(uid, "msg1", false, now), msg(uid, "msg2", true, now.minusSeconds(60))));
        when(messageRepository.countByRecipientAndRead(uid, false)).thenReturn(1L);

        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.unread_count").value(1))
                .andExpect(jsonPath("$.data.messages[0].title").value("msg1"))
                .andExpect(jsonPath("$.data.messages[0].read").value(false))
                .andExpect(jsonPath("$.data.messages[1].read").value(true))
                .andExpect(jsonPath("$.data.limit").value(20));
    }

    @Test
    void list_unreadOnly_filtersUnread() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        when(messageRepository.findByRecipientAndReadOrderByCreatedAtDesc(uid, false))
                .thenReturn(List.of());
        when(messageRepository.countByRecipientAndRead(uid, false)).thenReturn(0L);

        mockMvc.perform(get("/api/messages").param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.unread_count").value(0))
                .andExpect(jsonPath("$.data.messages").isArray());
    }

    @Test
    void list_invalidBefore_returns400() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);

        mockMvc.perform(get("/api/messages").param("before", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("before 必须是 ISO-8601 instant"));
    }

    @Test
    void list_withCursor_returnsOlder() throws Exception {
        UUID uid = UUID.randomUUID();
        loginAs(uid);
        Instant cursor = Instant.parse("2026-09-14T10:00:00Z");
        when(messageRepository.findByRecipientAndCreatedAtLessThanOrderByCreatedAtDesc(
                eq(uid), eq(cursor), any())).thenReturn(List.of());
        when(messageRepository.countByRecipientAndRead(uid, false)).thenReturn(0L);

        mockMvc.perform(get("/api/messages").param("before", cursor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void markRead_existing_setsReadFlag() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        loginAs(uid);
        MessageEntity m = msg(uid, "t", false, Instant.now());
        m.setId(mid);
        when(messageRepository.findById(mid)).thenReturn(java.util.Optional.of(m));
        when(messageRepository.save(any())).thenReturn(m);

        mockMvc.perform(post("/api/messages/{id}/read", mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("marked read"));
    }

    @Test
    void markRead_notFound_returns404() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        loginAs(uid);
        when(messageRepository.findById(mid)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/messages/{id}/read", mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void markRead_otherUsersMessage_returns404() throws Exception {
        UUID uid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        loginAs(uid);
        MessageEntity m = msg(other, "t", false, Instant.now());
        m.setId(mid);
        when(messageRepository.findById(mid)).thenReturn(java.util.Optional.of(m));

        mockMvc.perform(post("/api/messages/{id}/read", mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
