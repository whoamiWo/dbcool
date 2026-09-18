package com.nocobase.realtime;

import java.util.UUID;

/**
 * STOMP destination 约定(统一实时消息总线)。
 *
 * <p>所有 destination 均带租户段 {@code /topic/t-<tenantId>.…},
 * 配合 {@link TenantSubscriptionInterceptor} 做租户隔离 ——
 * 客户端可以随意构造 destination,因此服务端必须在订阅时校验。
 *
 * <p>之所以采用 {@code t-<tenantId>} 而非裸 tenantId,是为了让
 * {@link #parseTenantId(String)} 能稳定地按 {@code .} 截断解析
 * (tenantId 自身含下划线但不含点,如 {@code tenant_default})。
 */
public final class StompDestinations {

    private StompDestinations() {}

    /** IM 端点路径(与既有 /ws/alerts 原生端点区分,避免前缀冲突)。 */
    public static final String ENDPOINT = "/ws/im";

    /** 客户端发送消息的应用前缀。 */
    public static final String APP_PREFIX = "/app";

    /** 租户 topic 前缀。 */
    public static String tenantPrefix(String tenantId) {
        return "/topic/t-" + tenantId + ".";
    }

    /** 频道消息 topic。 */
    public static String channelTopic(String tenantId, UUID channelId) {
        return tenantPrefix(tenantId) + "channel." + channelId;
    }

    /** 告警 topic(供 AlertCenter 订阅,替代原单 JVM 内存广播)。 */
    public static String alertsTopic(String tenantId) {
        return tenantPrefix(tenantId) + "alerts";
    }

    /** 协同编辑广播 topic(多人同时编辑同一文档时接收增量)。 */
    public static String collabTopic(String tenantId, String docId) {
        return tenantPrefix(tenantId) + "collab." + docId;
    }

    /**
     * 协同编辑客户端发送增量更新的应用目的地。
     *
     * <p>客户端 send 到 {@code /app/<...>.collab.<docId>.update},
     * 服务端收到后广播至 {@link #collabTopic(String, String)}。
     */
    public static String collabUpdateApp(String tenantId, String docId) {
        return APP_PREFIX + "/t-" + tenantId + ".collab." + docId + ".update";
    }

    /** 用户私有队列(点对点推送,如提及/未读提醒)。 */
    public static String userQueue(String tenantId, UUID userId) {
        return "/queue/t-" + tenantId + ".user." + userId;
    }

    /**
     * 从 destination 解析租户 id;解析不出返回 {@code null}。
     *
     * <p>形如 {@code /topic/t-tenant_default.channel.<uuid>} → {@code tenant_default}
     */
    public static String parseTenantId(String destination) {
        if (destination == null || destination.isBlank()) return null;
        int idx = destination.indexOf("t-");
        if (idx < 0) return null;
        int start = idx + 2;
        if (start >= destination.length()) return null;
        int end = destination.indexOf('.', start);
        if (end < 0) return null;
        return destination.substring(start, end);
    }
}
