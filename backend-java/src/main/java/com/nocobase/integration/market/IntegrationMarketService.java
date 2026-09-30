package com.nocobase.integration.market;

import com.nocobase.plugin.PluginManifest;
import com.nocobase.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 集成市场 — 汇总「内置嵌入渠道」与「已注册插件」。
 */
@Service
public class IntegrationMarketService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationMarketService.class);

    /** 内置嵌入渠道 (kind=builtin,不可安装，需在「通知渠道」中配置)。 */
    private static final List<Map<String, Object>> BUILTIN_CHANNELS = List.of(
            Map.of("id", "dingtalk", "name", "钉钉", "kind", "builtin",
                    "description", "钉钉群机器人 / SSO 免密登录 / OA 审批回调"),
            Map.of("id", "wechat-work", "name", "企业微信", "kind", "builtin",
                    "description", "企业微信应用消息与通知推送"),
            Map.of("id", "slack", "name", "Slack", "kind", "builtin",
                    "description", "Slack OAuth / 发消息 / 事件回调"),
            Map.of("id", "mattermost", "name", "Mattermost", "kind", "builtin",
                    "description", "Mattermost Webhook / 消息收发"),
            Map.of("id", "feishu", "name", "飞书", "kind", "builtin",
                    "description", "飞书 OAuth / IM 消息 / 事件回调"),
            Map.of("id", "email", "name", "邮件", "kind", "builtin",
                    "description", "SMTP 邮件通知"),
            Map.of("id", "webhook", "name", "Webhook", "kind", "builtin",
                    "description", "通用 HTTP 回调，可对接第三方系统")
    );

    private final PluginRegistry pluginRegistry;
    private final TenantIntegrationInstallRepository installRepository;

    public IntegrationMarketService(PluginRegistry pluginRegistry,
                                     TenantIntegrationInstallRepository installRepository) {
        this.pluginRegistry = pluginRegistry;
        this.installRepository = installRepository;
    }

    /** 市场列表：内置渠道 + 已注册插件，附带本租户安装状态。 */
    public List<Map<String, Object>> listApps(String tenantId) {
        List<Map<String, Object>> apps = new ArrayList<>();

        // 内置渠道
        for (Map<String, Object> c : BUILTIN_CHANNELS) {
            Map<String, Object> app = new LinkedHashMap<>(c);
            String appId = (String) c.get("id");
            boolean installed = installRepository.existsByTenantIdAndAppKey(tenantId, appId);
            app.put("installed", installed);
            apps.add(app);
        }

        // 插件
        for (Map.Entry<String, PluginManifest> e : pluginRegistry.snapshot().entrySet()) {
            PluginManifest m = e.getValue();
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("id", m.name());
            app.put("name", m.name());
            app.put("version", m.version() == null ? "" : m.version());
            app.put("description", m.description() == null ? "" : m.description());
            app.put("permissions", m.permissions() == null ? List.of() : m.permissions());
            app.put("kind", "plugin");
            boolean installed = installRepository.existsByTenantIdAndAppKey(tenantId, m.name());
            app.put("installed", installed);
            apps.add(app);
        }

        log.debug("[Market] listApps tenant={}, total={}", tenantId, apps.size());
        return apps;
    }

    /**
     * 安装应用。
     */
    @Transactional
    public Map<String, Object> installApp(String appId, String tenantId, UUID userId) {
        if (appId == null || appId.isBlank()) {
            return Map.of("code", 400, "message", "appId 必填");
        }
        if (tenantId == null || tenantId.isBlank()) {
            return Map.of("code", 400, "message", "tenantId 必填");
        }

        // 检查是否已安装
        if (installRepository.existsByTenantIdAndAppKey(tenantId, appId)) {
            return Map.of("code", 0, "message", "already installed",
                    "data", Map.of("id", appId, "installed", true));
        }

        // 内置渠道
        for (Map<String, Object> c : BUILTIN_CHANNELS) {
            if (appId.equals(c.get("id"))) {
                String appName = (String) c.get("name");
                TenantIntegrationInstallEntity install = new TenantIntegrationInstallEntity(
                        tenantId, appId, appName, "1.0.0"
                );
                install.setInstalledBy(userId);
                installRepository.save(install);
                return Map.of("code", 0, "message", "installed",
                        "data", Map.of("id", appId, "installed", true, "name", appName));
            }
        }
        // 插件
        PluginManifest manifest = pluginRegistry.get(appId);
        if (manifest == null) {
            return Map.of("code", 404, "message", "未找到该应用 (插件未注册): " + appId);
        }
        TenantIntegrationInstallEntity install = new TenantIntegrationInstallEntity(
                tenantId, appId, manifest.name(), manifest.version() != null ? manifest.version() : "1.0.0"
        );
        install.setInstalledBy(userId);
        installRepository.save(install);
        return Map.of("code", 0, "message", "installed",
                "data", Map.of(
                        "id", manifest.name(),
                        "version", manifest.version() == null ? "" : manifest.version(),
                        "permissions", manifest.permissions() == null ? List.of() : manifest.permissions()
                ));
    }

    /** 已注册插件数量 (供工作台概览)。 */
    public int pluginCount() {
        return pluginRegistry.size();
    }

    /**
     * 卸载应用。
     */
    @Transactional
    public Map<String, Object> uninstallApp(String appId, String tenantId) {
        if (appId == null || appId.isBlank()) {
            return Map.of("code", 400, "message", "appId 必填");
        }
        if (tenantId == null || tenantId.isBlank()) {
            return Map.of("code", 400, "message", "tenantId 必填");
        }

        Optional<TenantIntegrationInstallEntity> existing = installRepository.findByTenantIdAndAppKey(tenantId, appId);
        if (existing.isPresent()) {
            installRepository.delete(existing.get());
        }

        // 内置渠道
        for (Map<String, Object> c : BUILTIN_CHANNELS) {
            if (appId.equals(c.get("id"))) {
                return Map.of("code", 0, "message",
                        "uninstalled",
                        "data", Map.of("id", appId, "tenantId", tenantId));
            }
        }
        // 插件
        PluginManifest manifest = pluginRegistry.get(appId);
        if (manifest == null) {
            return Map.of("code", 404, "message", "未找到该应用 (插件未注册): " + appId);
        }
        return Map.of("code", 0, "message", "uninstalled",
                "data", Map.of(
                        "id", manifest.name(),
                        "tenantId", tenantId,
                        "note", "插件请通过部署目录移除 jar 重启后生效"
                ));
    }
}
