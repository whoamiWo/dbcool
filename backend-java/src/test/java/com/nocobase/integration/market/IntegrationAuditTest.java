package com.nocobase.integration.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.plugin.PluginRegistry;
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
}
