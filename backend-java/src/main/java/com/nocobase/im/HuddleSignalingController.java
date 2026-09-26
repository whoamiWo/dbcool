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
 * <p><b>原桩代码已清零</b>：此前 3 个 STOMP 端点（join/leave/signal）仅做
 * log.info + 硬编码字符串返回，无房间管理、无鉴权、无真实信令转发。
 * 本阶段明确下线：保留端点防止前端路由断裂，但返回 501/明确错误，
 * 同时记录 ERROR 告警，提示运维需要真实化或明确下线前端入口。
 *
 * <p>生产环境建议：若需真实音视频，替换为独立 WebRTC 服务（如 Janus/mediasoup）
 * 并通过此控制器做鉴权 + 房间状态查询，而非在此做信令转发。
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