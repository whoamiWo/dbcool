package com.nocobase.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE 55 Stage 4 — Huddle WebRTC 信令控制器。
 *
 * <p><b>STOMP 路径的桩代码已清零（本文件仅此一处）</b>：此前 3 个 STOMP 端点
 * （join/leave/signal）仅做 log.info + 硬编码字符串返回（假成功），已改为 501 明确下线。
 *
 * <p><b>重要澄清（勿误解）</b>：Huddle 真实信令<b>已实现且已生效</b>，走的是
 * 原生 WebSocket {@code /ws/huddle} —— 由 {@code HuddleWebSocketConfig} 注册
 * {@code HuddleSignalingHandler}（含房间管理 rooms/sessionRooms、join/leave/relay 转发、
 * StompHandshakeInterceptor JWT 鉴权）。前端 HuddlePanel / useHuddle 连的就是 /ws/huddle，
 * <b>不走本 STOMP 路径</b>，因此本文件改 501 对线上音视频功能<b>无影响</b>。
 *
 * <p>本 STOMP 控制器为历史遗留的第二套入口，前端未使用，保留 501 仅为避免
 * 未来误接时产生"假成功"。如需启用 STOMP 信令，应复用 HuddleSignalingHandler 逻辑。
 */
@Controller
public class HuddleSignalingController {

    private static final Logger log = LoggerFactory.getLogger(HuddleSignalingController.class);

    @MessageMapping("/huddle.join")
    @SendTo("/topic/huddle/{roomId}")
    public String handleJoin(@Payload String payload) throws Exception {
        log.error("[huddle] STOMP /huddle.join 被调用但服务未真实化 — 端点已下线(501) payload={}", payload);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Huddle 音视频信令服务未真实化（PHASE 55 Stage 4 下线）");
    }

    @MessageMapping("/huddle.leave")
    @SendTo("/topic/huddle/{roomId}")
    public String handleLeave(@Payload String payload) throws Exception {
        log.error("[huddle] STOMP /huddle.leave 被调用但服务未真实化 — 端点已下线(501) payload={}", payload);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Huddle 音视频信令服务未真实化（PHASE 55 Stage 4 下线）");
    }

    @MessageMapping("/huddle.signal")
    @SendTo("/topic/huddle/{roomId}")
    public String handleSignal(@Payload String payload) throws Exception {
        log.error("[huddle] STOMP /huddle.signal 被调用但服务未真实化 — 端点已下线(501) payload={}", payload);
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Huddle 音视频信令服务未真实化（PHASE 55 Stage 4 下线）");
    }
}