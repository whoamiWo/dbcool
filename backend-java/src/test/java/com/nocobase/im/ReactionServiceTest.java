package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.im.entity.ImMessageReactionEntity;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE82：取消回应的归属校验。
 *
 * <p>{@code findByMessageIdAndUserIdAndEmoji} 三元组命中只说明"这条回应存在"，
 * 不能推出"它属于当前租户" —— 因此命中之后仍要比对 {@code tenantId}。
 */
class ReactionServiceTest {

    private ImReactionRepository repo;
    private ReactionService service;
    private AuditService auditService;

    private final UUID messageId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ImReactionRepository.class);
        auditService = mock(AuditService.class);
        service = new ReactionService(repo, auditService);
    }

    private ImMessageReactionEntity reaction(String tenantId) {
        ImMessageReactionEntity r = new ImMessageReactionEntity();
        r.setId(UUID.randomUUID());
        r.setMessageId(messageId);
        r.setUserId(userId);
        r.setEmoji("+1");
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    void remove_sameTenant_deletes() {
        ImMessageReactionEntity r = reaction("tenant_default");
        when(repo.findByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(Optional.of(r));

        service.remove(messageId, userId, "+1", "tenant_default");

        verify(repo).delete(r);
    }

    @Test
    void remove_tenantMismatch_throws403() {
        ImMessageReactionEntity r = reaction("tenant_other");
        when(repo.findByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.remove(messageId, userId, "+1", "tenant_default"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(repo, never()).delete(any());
    }
}
