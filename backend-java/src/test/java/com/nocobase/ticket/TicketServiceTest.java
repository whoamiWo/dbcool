package com.nocobase.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void updateStatus_sameTenant_updatesStatus() {
        TicketEntity t = ticketOf("tenant_A");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        TicketEntity out = service.updateStatus(t.getId(), TicketEntity.Status.RESOLVED, "tenant_A");

        assertThat(out.getStatus()).isEqualTo(TicketEntity.Status.RESOLVED);
        assertThat(out.getResolvedAt()).isNotNull();
    }

    @Test
    void updateStatus_tenantMismatch_403() {
        TicketEntity t = ticketOf("tenant_A");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.updateStatus(t.getId(), TicketEntity.Status.CLOSED, "tenant_B"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .matches(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode() == FORBIDDEN);
        // Status should remain unchanged (default OPEN)
        assertThat(t.getStatus()).isEqualTo(TicketEntity.Status.OPEN);
    }

    @Test
    void createTicket_persistAndReturn() {
        when(ticketRepository.save(any(TicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TicketEntity out = service.createTicket("t1", "sess1", "Alice", "alice@example.com", "Bug report");
        assertThat(out.getTenantId()).isEqualTo("t1");
        assertThat(out.getSessionId()).isEqualTo("sess1");
        assertThat(out.getCustomerName()).isEqualTo("Alice");
        assertThat(out.getCustomerEmail()).isEqualTo("alice@example.com");
        assertThat(out.getMessage()).isEqualTo("Bug report");
        assertThat(out.getStatus()).isEqualTo(TicketEntity.Status.OPEN);
    }

    @Test
    void saveMessage_newSession_createsTicket() {
        when(ticketRepository.findBySessionIdAndTenantId("sessX", "tX")).thenReturn(List.of());
        when(ticketRepository.save(any(TicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TicketEntity out = service.saveMessage("sessX", "tX", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "Hi", "u@x.com");
        assertThat(out.getTenantId()).isEqualTo("tX");
        assertThat(out.getSessionId()).isEqualTo("sessX");
        assertThat(out.getCustomerEmail()).isEqualTo("u@x.com");
        assertThat(out.getMessage()).isEqualTo("Hi");
        assertThat(out.getCreatedAt()).isNotNull();
    }

    @Test
    void saveMessage_existingSession_updatesMessagePreservesEmail() {
        TicketEntity existing = new TicketEntity();
        existing.setTenantId("tY");
        existing.setSessionId("sessY");
        existing.setCustomerEmail("old@email.com");
        existing.setCustomerName("OldUser");
        when(ticketRepository.findBySessionIdAndTenantId("sessY", "tY")).thenReturn(List.of(existing));
        when(ticketRepository.save(any(TicketEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TicketEntity out = service.saveMessage("sessY", "tY", null, "New message", "new@email.com");
        assertThat(out.getCustomerEmail()).isEqualTo("old@email.com"); // preserved
        assertThat(out.getCustomerName()).isEqualTo("OldUser"); // preserved
        assertThat(out.getMessage()).isEqualTo("New message");
    }

    @Test
    void findTicketById_found() {
        TicketEntity t = ticketOf("tZ");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        TicketEntity found = service.findTicketById(t.getId());
        assertThat(found).isSameAs(t);
    }

    @Test
    void findTicketById_notFound_throws() {
        when(ticketRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findTicketById(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ticket not found");
    }
}
