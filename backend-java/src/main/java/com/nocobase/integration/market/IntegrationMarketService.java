package com.nocobase.integration.market;

import com.nocobase.plugin.PluginManifest;
import com.nocobase.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集成市场 — 汇总「内置嵌入渠道」与「已注册插件」。
 *
 * <p><b>Week 44 接真</b>:此前 listApps 硬编码三个应用,与实际插件体系脱节。
 * 现改为:
 * <ul>
 *   <li>内置渠道:钉钉 / 企业微信 / 邮件 / Webhook(走通知渠道配置,无需安装)</li>
 *   <li>扩展插件:读取 {@link PluginRegistry#snapshot()} 的真实插件注册表</li>
 * </ul>
 */
@Service
public class IntegrationMarketService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationMarketService.class);

    /** 内置嵌入渠道(kind=builtin,不可安装,需在「通知渠道」中配置)。 */
    private static final List<Map<String, Object>> BUILTIN_CHANNELS = List.of(
            Map.of("id", "dingtalk", "name", "钉钉", "kind", "builtin",
                    "description", "钉钉群机器人 / SSO 免密登录 / OA 审批回调"),
            Map.of("id", "wechat-work", "name", "企业微信", "kind", "builtin",
                    "description", "企业微信应用消息与通知推送"),
            Map.of("id", "email", "name", "邮件", "kind", "builtin",
                    "description", "SMTP 邮件通知"),
            Map.of("id", "webhook", "name", "Webhook", "kind", "builtin",
                    "description", "通用 HTTP 回调,可对接第三方系统")
    );

    private final PluginRegistry pluginRegistry;

    public IntegrationMarketService(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    /** 市场列表:内置渠道 + 已注册插件。 */
    public List<Map<String, Object>> listApps(String tenantId) {
        List<Map<String, Object>> apps = new ArrayList<>(BUILTIN_CHANNELS);

        for (Map.Entry<String, PluginManifest> e : pluginRegistry.snapshot().entrySet()) {
            PluginManifest m = e.getValue();
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("id", m.name());
            app.put("name", m.name());
            app.put("version", m.version() == null ? "" : m.version());
            app.put("description", m.description() == null ? "" : m.description());
            app.put("permissions", m.permissions() == null ? List.of() : m.permissions());
            app.put("kind", "plugin");
            app.put("status", "installed");
            apps.add(app);
        }

        log.debug("[Market] listApps tenant={}, total={}", tenantId, apps.size());
        return apps;
    }

    /**
     * 安装应用。
     *
     * <p>插件由 {@code PluginFileScanner} 扫描目录自动注册,因此"安装"语义为
     * 校验并回显插件信息;内置渠道直接引导去配置通知渠道。
     */
    public Map<String, Object> installApp(String appId, String tenantId) {
        if (appId == null || appId.isBlank()) {
            return Map.of("code", 400, "message", "appId 必填");
        }
        // 内置渠道
        for (Map<String, Object> c : BUILTIN_CHANNELS) {
            if (appId.equals(c.get("id"))) {
                return Map.of("code", 0, "message",
                        "内置渠道无需安装,请在「通知渠道」中配置后使用",
                        "data", c);
            }
        }
        // 插件
        PluginManifest manifest = pluginRegistry.get(appId);
        if (manifest == null) {
            return Map.of("code", 404, "message", "未找到该应用(插件未注册): " + appId);
        }
        return Map.of("code", 0, "message", "installed",
                "data", Map.of(
                        "id", manifest.name(),
                        "version", manifest.version() == null ? "" : manifest.version(),
                        "permissions", manifest.permissions() == null ? List.of() : manifest.permissions()
                ));
    }

    /** 已注册插件数量(供工作台概览)。 */
    public int pluginCount() {
        return pluginRegistry.size();
    }
}
