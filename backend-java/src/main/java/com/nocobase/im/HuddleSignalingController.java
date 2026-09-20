package com.nocobase.im;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class HuddleSignalingController {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(HuddleSignalingController.class);

    /**
     * 客户端订阅 /topic/huddle/{roomId} 后，向 /app/huddle.join 发送 join 请求。
     */
    @MessageMapping("/huddle.join")
    @SendTo("/topic/huddle/{roomId}")
    public String handleJoin(@Payload String payload) throws Exception {
        // TODO: 解析 JSON 获取 roomId，验证用户身份，加入房间
        log.info("Huddle join: {}", payload);
        return "{\"type\":\"joined\"}";
    }

    /**
     * 离开房间
     */
    @MessageMapping("/huddle.leave")
    @SendTo("/topic/huddle/{roomId}")
    public String handleLeave(@Payload String payload) throws Exception {
        log.info("Huddle leave: {}", payload);
        return "{\"type\":\"left\"}";
    }

    /**
     * WebRTC offer/answer/ICE candidate 转发
     */
    @MessageMapping("/huddle.signal")
    @SendTo("/topic/huddle/{roomId}")
    public String handleSignal(@Payload String payload) throws Exception {
        log.info("Huddle signal: {}", payload);
        return payload;
    }
}