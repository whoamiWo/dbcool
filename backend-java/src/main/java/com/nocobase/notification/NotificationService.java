package com.nocobase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多渠道通知服务.
 * 流程:
 *   1. 根据 tenant_id 查所有 enabled channels
 *   2. 按 type 路由到对应 Dispatcher
 *   3. 调用 dispatcher.send() 拿到结果
 *
 * 与 message 模块协同:ed/send 同时也走 messageRepository.save()(InApp).
 * 但目前设计:多渠道通过 notification channel 机制显式管理,不再隐式插入站内信.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationChannelRepository repository;
    private final Map<NotificationChannelEntity.Type, NotificationDispatcher> dispatchers;

    public NotificationService(NotificationChannelRepository repository,
                                List<NotificationDispatcher> dispatcherList) {
        this.repository = repository;
        this.dispatchers = dispatcherList.stream()
                .collect(Collectors.toMap(NotificationDispatcher::supportedType, d -> d));
    }

    /**
     * 触发一个事件 — 发送到所有匹配 channels.
     * @param eventName 如 "workflow.approve" / "workflow.trigger" / "record.create"(可为 null 表示全部)
     */
    public List<NotificationDispatcher.SendResult> fire(String tenantId,
                                                         String eventName,
                                                         String recipient,
                                                         Map<String, Object> payload) {
        List<NotificationChannelEntity> channels = repository.findEnabledByTenantId(tenantId);
        List<NotificationDispatcher.SendResult> results = new ArrayList<>();
        for (NotificationChannelEntity ch : channels) {
            if (!matchesEvent(ch, eventName)) continue;
            NotificationDispatcher d = dispatchers.get(ch.getType());
            if (d == null) {
                log.warn("no dispatcher for type={} channel={}", ch.getType(), ch.getName());
                continue;
            }
            try {
                NotificationDispatcher.SendResult r = d.send(ch, recipient, payload);
                log.info("notify channel={} type={} event={} success={} detail={}",
                        ch.getName(), ch.getType(), eventName, r.success(), r.detail());
                results.add(r);
            } catch (Exception e) {
                log.warn("notify exception channel={} err={}", ch.getName(), e.getMessage());
                results.add(NotificationDispatcher.SendResult.error(
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return results;
    }

    /** 测试单 channel 发送 — admin UI "测试" 按钮. */
    public NotificationDispatcher.SendResult testSend(UUID channelId, String recipient,
                                                       Map<String, Object> payload) {
        NotificationChannelEntity ch = repository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("channel not found: " + channelId));
        NotificationDispatcher d = dispatchers.get(ch.getType());
        if (d == null) return NotificationDispatcher.SendResult.error("no dispatcher: " + ch.getType());
        return d.send(ch, recipient, payload);
    }

    private boolean matchesEvent(NotificationChannelEntity channel, String eventName) {
        if (eventName == null || eventName.isBlank()) return true;
        String events = channel.getEvents();
        if (events == null || events.isBlank()) return true; // 空 = 全部事件
        for (String e : events.split(",")) {
            if (e.trim().equalsIgnoreCase(eventName)) return true;
        }
        return false;
    }
}
