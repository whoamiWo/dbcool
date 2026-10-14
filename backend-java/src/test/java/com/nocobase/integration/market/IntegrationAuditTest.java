package com.nocobase.integration.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.plugin.PluginManifest;
import com.nocobase.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PHASE99 T3 专项测试：集成安装/卸载审计。
 *
 * <p>验证验收标准：安装/卸载时 {@link AuditService#log} 被调用，
 * 且记录含 tenantId、provider、操作人。
 */
class IntegrationAuditTest {

    private TenantIntegrationInstallRepository installRepository;
    private PluginRegistry pluginRegistry;
    private AuditService auditService;
    private IntegrationMarketService service;

    @BeforeEach
    void setUp() {
        installRepository = mock(TenantIntegrationInstallRepository.class);
        pluginRegistry = mock(PluginRegistry.class);
        auditService = mock(AuditService.class);
        service = new IntegrationMarketService(pluginRegistry, installRepository, auditService);
    }

    @Test
    @DisplayName("安装内置渠道记录审计")
    void installApp_builtinChannel_auditLogged() {
        String tenantId = "tenant_a";
        String appId = "dingtalk";
        UUID userId = UUID.randomUUID();

        when(installRepository.existsByTenantIdAndAppKey(tenantId, appId)).thenReturn(false);

        Map<String, Object> result = service.installApp(appId, tenantId, userId);

        assertThat(result.get("code")).isEqualTo(0);
        verify(auditService).log(
                eq(tenantId), eq(userId), eq(null),
                eq("integration.install"), eq("integration"), eq(appId),
                any());
    }

    @Test
    @DisplayName("卸载应用记录审计")
    void uninstallApp_auditLogged() {
        String tenantId = "tenant_a";
        String appId = "dingtalk";

        when(installRepository.findByTenantIdAndAppKey(tenantId, appId))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.uninstallApp(appId, tenantId);

        assertThat(result.get("code")).isEqualTo(0);
        verify(auditService).log(
                eq(tenantId), eq(null), eq(null),
                eq("integration.uninstall"), eq("integration"), eq(appId),
                any());
    }

    @Test
    @DisplayName("卸载时记录 wasInstalled 状态")
    void uninstallApp_auditContainsWasInstalled() {
        String tenantId = "tenant_a";
        String appId = "dingtalk";

        // 已安装的场景
        var existing = new TenantIntegrationInstallEntity(tenantId, appId, "钉钉", "1.0.0");
        when(installRepository.findByTenantIdAndAppKey(tenantId, appId))
                .thenReturn(Optional.of(existing));

        service.uninstallApp(appId, tenantId);

        verify(auditService).log(
                eq(tenantId), eq(null), eq(null),
                eq("integration.uninstall"), eq("integration"), eq(appId),
                eq(Map.of("tenantId", tenantId, "wasInstalled", true)));
    }

    @Test
    @DisplayName("listApps 返回内置渠道 + 插件并标记安装状态")
    void listApps_returnsBuiltinAndPlugins() {
        String tenantId = "tenant_x";
        String appId = "dingtalk";
        when(installRepository.existsByTenantIdAndAppKey(tenantId, appId)).thenReturn(true);
        when(installRepository.existsByTenantIdAndAppKey(tenantId, "slack")).thenReturn(false);
        when(installRepository.existsByTenantIdAndAppKey(tenantId, "custom-plugin")).thenReturn(false);

        PluginManifest pm = new PluginManifest("custom-plugin", "2.0.0", "自定义插件", List.of("read"), false);
        when(pluginRegistry.snapshot()).thenReturn(Map.of("custom-plugin", pm));

        List<Map<String, Object>> apps = service.listApps(tenantId);

        assertThat(apps).isNotEmpty();
        Map<String, Object> dingtalk = apps.stream()
                .filter(m -> "dingtalk".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(dingtalk.get("installed")).isEqualTo(Boolean.TRUE);
        assertThat(dingtalk.get("kind")).isEqualTo("builtin");

        Map<String, Object> custom = apps.stream()
                .filter(m -> "custom-plugin".equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(custom.get("kind")).isEqualTo("plugin");
        assertThat(custom.get("installed")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("installApp 空 appId 返回 400")
    void installApp_emptyAppId_returns400() {
        Map<String, Object> r = service.installApp("", "t1", UUID.randomUUID());
        assertThat(r.get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("installApp 空 tenantId 返回 400")
    void installApp_emptyTenantId_returns400() {
        Map<String, Object> r = service.installApp("dingtalk", "", UUID.randomUUID());
        assertThat(r.get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("installApp 已安装返回 already installed")
    void installApp_alreadyInstalled_returnsAlreadyInstalled() {
        String tenantId = "t1";
        String appId = "dingtalk";
        when(installRepository.existsByTenantIdAndAppKey(tenantId, appId)).thenReturn(true);

        Map<String, Object> r = service.installApp(appId, tenantId, UUID.randomUUID());

        assertThat(r.get("code")).isEqualTo(0);
        assertThat(r.get("message")).isEqualTo("already installed");
    }

    @Test
    @DisplayName("installApp 插件安装成功并记录审计")
    void installApp_plugin_successAndAuditLogged() {
        String tenantId = "t1";
        String appId = "custom-plugin";
        UUID userId = UUID.randomUUID();

        when(installRepository.existsByTenantIdAndAppKey(tenantId, appId)).thenReturn(false);
        PluginManifest pm = new PluginManifest(appId, "2.0.0", "自定义插件", List.of("read"), false);
        when(pluginRegistry.get(appId)).thenReturn(pm);

        Map<String, Object> r = service.installApp(appId, tenantId, userId);

        assertThat(r.get("code")).isEqualTo(0);
        verify(auditService).log(
                eq(tenantId), eq(userId), eq(null),
                eq("integration.install"), eq("integration"), eq(appId),
                any());
    }

    @Test
    @DisplayName("installApp 插件不存在返回 404")
    void installApp_pluginNotFound_returns404() {
        String tenantId = "t1";
        String appId = "unknown-plugin";

        when(installRepository.existsByTenantIdAndAppKey(tenantId, appId)).thenReturn(false);
        when(pluginRegistry.get(appId)).thenReturn(null);

        Map<String, Object> r = service.installApp(appId, tenantId, UUID.randomUUID());

        assertThat(r.get("code")).isEqualTo(404);
        assertThat(r.get("message")).asString().contains("未找到");
    }

    @Test
    @DisplayName("uninstallApp 空 appId 返回 400")
    void uninstallApp_emptyAppId_returns400() {
        Map<String, Object> r = service.uninstallApp("", "t1");
        assertThat(r.get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("uninstallApp 空 tenantId 返回 400")
    void uninstallApp_emptyTenantId_returns400() {
        Map<String, Object> r = service.uninstallApp("dingtalk", "");
        assertThat(r.get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("uninstallApp 插件卸载成功并返回 note")
    void uninstallApp_plugin_successReturnsNote() {
        String tenantId = "t1";
        String appId = "custom-plugin";

        when(installRepository.findByTenantIdAndAppKey(tenantId, appId)).thenReturn(Optional.empty());
        PluginManifest pm = new PluginManifest(appId, "2.0.0", "自定义插件", List.of("read"), false);
        when(pluginRegistry.get(appId)).thenReturn(pm);

        Map<String, Object> r = service.uninstallApp(appId, tenantId);

        assertThat(r.get("code")).isEqualTo(0);
        assertThat(r.get("data")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) r.get("data")).get("note")).asString().contains("jar");
    }

    @Test
    @DisplayName("uninstallApp 插件未注册返回 404")
    void uninstallApp_pluginNotRegistered_returns404() {
        String tenantId = "t1";
        String appId = "unknown-plugin";

        when(installRepository.findByTenantIdAndAppKey(tenantId, appId)).thenReturn(Optional.empty());
        when(pluginRegistry.get(appId)).thenReturn(null);

        Map<String, Object> r = service.uninstallApp(appId, tenantId);

        assertThat(r.get("code")).isEqualTo(404);
        assertThat(r.get("message")).asString().contains("未找到");
    }
}
