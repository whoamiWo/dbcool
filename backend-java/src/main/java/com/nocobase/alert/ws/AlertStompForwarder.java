package com.nocobase.alert.ws;

import org.springframework.stereotype.Component;

import com.nocobase.alert.AlertCollector;
import com.nocobase.alert.AlertEvent;
import com.nocobase.realtime.RedisStompBridge;
import com.nocobase.realtime.StompDestinations;
import com.nocobase.tenant.TenantContext;

/**
 * 任务 6:把告警推送从「单 JVM 内存广播」切入「统一实时消息总线」。
 *
 * <p>作为 {@link AlertCollector.Listener} 在 {@code emit} 时把事件投到
 * {@code /topic/t-<tenantId>.alerts},由 {@link RedisStompBridge} 经 Redis
 * Pub/Sub 做**跨实例**投递,替代原先只在单个 JVM 内生效的
 * {@code AlertBroadcaster} 内存会话集合。
 *
 * <p><strong>注册时机</strong>:在构造阶段 {@code addListener},早于
 * {@link AlertBroadcastInitializer} 的 {@code ContextRefreshedEvent}。
 * 后者有 {@code listenerCount() == 0} 保护,会因本类已注册而不再挂载旧的
 * {@code RoutedAlertBroadcaster},从而实现「新总线替代旧广播」而非双份推送。
 *
 * <p><strong>陷阱 2(必读,勿误解)</strong>:当前 Java 侧
 * {@code AlertCollector.emit()} 与 {@code AlertStore.insertAlert()} 在生产代码中
 * <em>零调用</em>(告警数据源尚未接入),因此切换后告警页面收不到数据属**预期现象**,
 * 不是本类的缺陷。严禁为了看到效果而伪造 {@code emit} 调用或编造假数据。
 *
 * <p><strong>陷阱 1</strong>:{@code emit} 所在线程不一定经过 {@code JwtAuthFilter},
 * {@code TenantContext} 可能未设置;此处用 {@link TenantContext#currentTenantId()}
 * 取值,它已内置兜底为默认租户。
 */
@Component
public class AlertStompForwarder implements AlertCollector.Listener {

    private final RedisStompBridge bridge;

    public AlertStompForwarder(AlertCollector collector, RedisStompBridge bridge) {
        this.bridge = bridge;
        if (collector != null) {
            collector.addListener(this);
        }
    }

    @Override
    public void onAlert(AlertEvent event) {
        if (event == null || bridge == null) {
            return;
        }
        String tenantId = TenantContext.currentTenantId();
        bridge.broadcast(StompDestinations.alertsTopic(tenantId), event.toMap());
    }
}
