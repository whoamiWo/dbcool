package com.nocobase.ticket;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单 REST API — Livechat 客服转工单。
 */
@RestController
@RequestMapping("/api/livechat")
public class TicketController {

    private final TicketService ticketService;
    private final AuditService auditService;

    public TicketController(TicketService ticketService, AuditService auditService) {
        this.ticketService = ticketService;
        this.auditService = auditService;
    }

    @PostMapping("/session")
    public Map<String, Object> createSession(@AuthenticationPrincipal AuthenticatedUser user) {
        String sessionId = UUID.randomUUID().toString();
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "ticket.session.create", "ticket_session", sessionId,
                Map.of("sessionId", sessionId));
        return Map.of("code", 0, "message", "success",
                "data", Map.of("sessionId", sessionId));
    }

    /** 发送消息（记录消息内容）。 */
    @PostMapping("/message")
    public Map<String, Object> sendMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        String sessionId = (String) body.get("sessionId");
        String message = (String) body.get("message");
        String customerEmail = (String) body.getOrDefault("customerEmail",
                user.username() + "@" + user.tenantId() + ".local");
        // PHASE 55 Stage 4: 消息真实持久化（禁丢弃）
        ticketService.saveMessage(sessionId, user.tenantId(), user.userId(), message, customerEmail);
        return Map.of("code", 0, "message", "success",
                "data", Map.of("sessionId", sessionId));
    }

    /** 结束会话并创建工单。 */
@PostMapping("/close")
    public Map<String, Object> closeSession(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        String sessionId = (String) body.get("sessionId");
        String customerName = user.username();
        String customerEmail = (String) body.getOrDefault("customerEmail",
                user.username() + "@" + user.tenantId() + ".local");
        String message = (String) body.get("message");

        TicketEntity ticket = ticketService.createTicket(
                user.tenantId(), sessionId, customerName, customerEmail, message);

        auditService.log(user.tenantId(), user.userId(), user.username(),
                "ticket.close", "ticket", ticket.getId().toString(),
                Map.of("sessionId", sessionId, "ticketId", ticket.getId().toString(),
                        "customerEmail", customerEmail));

        return Map.of("code", 0, "message", "success",
                "data", Map.of("ticketId", ticket.getId().toString()));
    }

    /** 查询租户工单列表。 */
    @GetMapping("/tickets")
    public Map<String, Object> listTickets(
            @AuthenticationPrincipal AuthenticatedUser user) {
        List<TicketEntity> tickets = ticketService.listByTenant(user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", Map.of("tickets", tickets));
    }

    /** 添加工单备注（客服操作）。 */
    @PostMapping("/tickets/{id}/notes")
    public Map<String, Object> addNote(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        String notes = (String) body.get("notes");
        // 额外校验：防止直接调用 service.addNote 时越权
        TicketEntity existing = ticketService.findTicketById(id);
        if (!existing.getTenantId().equals(user.tenantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant mismatch");
        }
        TicketEntity ticket = ticketService.addNote(id, notes, user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", Map.of("ticketId", ticket.getId().toString()));
    }
}
