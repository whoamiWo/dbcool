package com.nocobase.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

/**
 * 协同编辑 STOMP 入口 — 客户端通过 {@code /app/collab/*} 发送增量。
 *
 * <p><b>身份</b>:userId / tenantId 一律取自握手阶段写入会话的
 * {@link StompPrincipal}(由 {@code StompHandshakeInterceptor} 解析 JWT 生成),
 * 不接受客户端在消息体里自报身份 —— 防止越权往他人文档房间广播。
 *
 * <p><b>订阅</b>:客户端订阅 {@code /topic/t-<tenantId>.collab.<docId>},
 * 租户隔离由 {@code TenantSubscriptionInterceptor} 在订阅时校验。
 */
@Controller
public class RealtimeCollabController {

    private static final Logger log = LoggerFactory.getLogger(RealtimeCollabController.class);

    private final RealtimeService realtimeService;

    public RealtimeCollabController(RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    /** 加入文档协作房间:body = {docId} */
    @MessageMapping("/collab/join")
    public void join(@Payload Map<String, Object> body,
                     @Header("simpSessionAttributes") Map<String, Object> attrs) {
        StompPrincipal p = principal(attrs);
        if (p == null) return;
        String docId = str(body.get("docId"));
        if (docId == null) return;
        realtimeService.joinRoom(docId, p.userId(), String.valueOf(attrs.get("sessionId")));
        // 通知房间内其他人有新成员加入
        realtimeService.broadcastUpdate(docId, p.tenantId(), p.userId(),
                Map.of("type", "presence", "action", "join",
                        "username", p.username() == null ? "" : p.username(),
                        "activeUsers", realtimeService.getActiveUsers(docId)));
    }

    /** 离开文档协作房间:body = {docId} */
    @MessageMapping("/collab/leave")
    public void leave(@Payload Map<String, Object> body,
                      @Header("simpSessionAttributes") Map<String, Object> attrs) {
        StompPrincipal p = principal(attrs);
        if (p == null) return;
        String docId = str(body.get("docId"));
        if (docId == null) return;
        int remain = Math.max(realtimeService.getActiveUsers(docId) - 1, 0);
        realtimeService.leaveRoom(docId, p.userId());
        realtimeService.broadcastUpdate(docId, p.tenantId(), p.userId(),
                Map.of("type", "presence", "action", "leave",
                        "username", p.username() == null ? "" : p.username(),
                        "activeUsers", remain));
    }

    /**
     * 应用一条 CRDT 增量:body = {docId, update}
     *
     * <p>{@code update} 为 Yjs update 的 Base64 编码;服务端不解析内容,
     * 只递增版本号并广播给房间内其他成员。
     */
    @MessageMapping("/collab/update")
    public void update(@Payload Map<String, Object> body,
                       @Header("simpSessionAttributes") Map<String, Object> attrs) {
        StompPrincipal p = principal(attrs);
        if (p == null) return;
        String docId = str(body.get("docId"));
        String updateBase64 = str(body.get("update"));
        if (docId == null || updateBase64 == null) return;
        long version = realtimeService.applyUpdate(docId, p.tenantId(), p.userId(), updateBase64);
        log.debug("[Realtime] doc={} update v{} from user={}", docId, version, p.userId());
    }

    // ============================================================
    //  工具
    // ============================================================

    /** 从会话属性取握手身份;缺失则告警并拒绝(不信任客户端传参)。 */
    private StompPrincipal principal(Map<String, Object> attrs) {
        Object o = attrs == null ? null : attrs.get(StompPrincipal.KEY);
        if (o instanceof StompPrincipal p) return p;
        log.warn("[Realtime] 会话缺少 StompPrincipal,拒绝协同消息");
        return null;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /** 供测试/内部使用:构造一个 principal(避免暴露 record 构造细节)。 */
    static StompPrincipal principalOf(UUID userId, String username, String tenantId) {
        return new StompPrincipal(userId, username, tenantId);
    }
}
