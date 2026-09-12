package com.nocobase.notification;

import java.util.Map;

/**
 * 通知 channel 分发器接口。
 * 每种 type 的 channel (email / webhook / dingtalk / wechat_work) 有一个实现。
 */
public interface NotificationDispatcher {

    /** 支持的 channel type. */
    NotificationChannelEntity.Type supportedType();

    /**
     * 发送一条通知.
     * @param channel 配置好的 channel
     * @param recipient 收件人 — email 邮箱 / webhook URL(可不传,用 config) / 钉钉机器人 target
     * @param payload 通知内容 — title / body / data(json)
     * @return 发送结果 status + detail message
     */
    SendResult send(NotificationChannelEntity channel,
                    String recipient,
                    Map<String, Object> payload);

    /** 发送结果 */
    record SendResult(boolean success, String detail) {
        public static SendResult ok(String detail) { return new SendResult(true, detail); }
        public static SendResult error(String detail) { return new SendResult(false, detail); }
    }
}
