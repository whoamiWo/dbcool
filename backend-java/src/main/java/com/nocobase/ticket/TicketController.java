package com.nocobase.ticket;

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

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 创建会话（前端初始化时调用）。 */
    @PostMapping("/session")
    public Map<String, Object> createSession(@AuthenticationPrincipal AuthenticatedUser user) {
        String sessionId = UUID.randomUUID().toString();
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
        // PHASE 55 Stage 4: 禁伪造邮箱(@nocobase.local)；取请求传入，回退为租户域
        String customerEmail = (String) body.getOrDefault("customerEmail",
                user.username() + "@" + user.tenantId() + ".local");
        String message = (String) body.get("message");

        TicketEntity ticket = ticketService.createTicket(
                user.tenantId(), sessionId, customerName, customerEmail, message);

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
}
