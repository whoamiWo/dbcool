package com.nocobase.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * TicketService 租户归属校验测试（PHASE76 T1）。
 *
 * <p>重点：addNote 必须校验 ticket.tenantId 与调用方一致（跨租户 → 403）。
 */
class TicketServiceTest {

    private TicketRepository ticketRepository;
    private TicketService service;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        service = new TicketService(ticketRepository);
        when(ticketRepository.save(any(TicketEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private TicketEntity ticketOf(String tenantId) {
        TicketEntity t = new TicketEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        return t;
    }

    @Test
    void addNote_sameTenant_updatesNotes() {
        TicketEntity t = ticketOf("tenant_A");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        TicketEntity out = service.addNote(t.getId(), "已联系客户", "tenant_A");

        assertThat(out.getAgentNotes()).isEqualTo("已联系客户");
    }

    @Test
    void addNote_tenantMismatch_403() {
        // 关键断言：租户 B 的客服不得对租户 A 的工单写备注 → 403
        TicketEntity t = ticketOf("tenant_A");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.addNote(t.getId(), "越权写备注", "tenant_B"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .matches(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode() == FORBIDDEN);
        assertThat(t.getAgentNotes()).isNull(); // 未被修改
    }
}
