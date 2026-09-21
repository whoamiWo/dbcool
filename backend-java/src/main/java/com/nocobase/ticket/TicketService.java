package com.nocobase.ticket;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /** 创建工单（Livechat 会话结束时调用）。 */
    @Transactional
    public TicketEntity createTicket(String tenantId, String sessionId,
                                      String customerName, String customerEmail,
                                      String message) {
        TicketEntity ticket = new TicketEntity();
        ticket.setTenantId(tenantId);
        ticket.setSessionId(sessionId);
        ticket.setCustomerName(customerName);
        ticket.setCustomerEmail(customerEmail);
        ticket.setMessage(message);
        ticket.setStatus(TicketEntity.Status.OPEN);
        ticket.setCreatedAt(Instant.now());
        return ticketRepository.save(ticket);
    }

    /** 查询租户下的工单列表。 */
    public List<TicketEntity> listByTenant(String tenantId) {
        return ticketRepository.findByTenantId(tenantId);
    }

    /** 更新工单状态。 */
    @Transactional
    public TicketEntity updateStatus(UUID id, TicketEntity.Status status) {
        TicketEntity ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));
        ticket.setStatus(status);
        if (status == TicketEntity.Status.RESOLVED || status == TicketEntity.Status.CLOSED) {
            ticket.setResolvedAt(Instant.now());
        }
        return ticketRepository.save(ticket);
    }

    /** 添加工单备注。 */
    @Transactional
    public TicketEntity addNote(UUID id, String notes) {
        TicketEntity ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));
        ticket.setAgentNotes(notes);
        return ticketRepository.save(ticket);
    }
}
