package com.nocobase.ticket;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TicketController 租户隔离测试（PHASE76 T1）。
 *
 * <p>重点：跨租户访问 addNote 必须返回 403。
 */
class TicketControllerTest {

    private TicketService ticketService;
    private TicketController controller;
    private final AuditService auditService = new AuditService(null, new ObjectMapper());

    private final UUID ticketId = UUID.randomUUID();
    private final AuthenticatedUser alice = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_A");
    private final AuthenticatedUser bob = new AuthenticatedUser(UUID.randomUUID(), "bob", "tenant_B");

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        controller = new TicketController(ticketService, auditService);
    }

    @Test
    void addNote_crossTenant_403() {
        TicketEntity ticket = mock(TicketEntity.class);
        when(ticket.getTenantId()).thenReturn("tenant_A");
        when(ticketService.findTicketById(ticketId)).thenReturn(ticket);

        assertThatThrownBy(() -> controller.addNote(ticketId, Map.of("notes", "越权备注"), bob))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.FORBIDDEN);
    }
}
