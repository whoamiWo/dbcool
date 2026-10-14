package com.nocobase.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.plugin.entity.PluginMarketplaceAppEntity;
import com.nocobase.plugin.repository.PluginMarketplaceAppRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE105: PluginMarketplaceService 测试（全部方法 + 异常分支）。 */
class PluginMarketplaceServiceTest {

    private PluginMarketplaceAppRepository appRepository;
    private PluginRegistry pluginRegistry;
    private PluginMarketplaceService service;

    private static final String TENANT = "t1";
    private static final String APP_KEY = "demo-plugin";

    @BeforeEach
    void setUp() {
        appRepository = mock(PluginMarketplaceAppRepository.class);
        pluginRegistry = mock(PluginRegistry.class);
        service = new PluginMarketplaceService(appRepository, pluginRegistry);
    }

    private PluginMarketplaceAppEntity appOf(String status, String version, String latest,
                                             String manifestYaml) {
        PluginMarketplaceAppEntity e = new PluginMarketplaceAppEntity();
        e.setId(UUID.randomUUID());
        e.setAppKey(APP_KEY);
        e.setName("演示插件");
        e.setVersion(version);
        e.setLatestVersion(latest);
        e.setDescription("desc");
        e.setAuthor("dev");
        e.setCategory("tools");
        e.setStatus(status);
        e.setManifestYaml(manifestYaml);
        e.setTenantId(TENANT);
        e.setCreatedAt(Instant.now());
        return e;
    }

    private PluginMarketplaceAppEntity availableApp() {
        return appOf(PluginMarketplaceAppEntity.Status.AVAILABLE.name(),
                "1.0.0", "1.0.0", "name: demo-plugin\nversion: 1.0.0\n");
    }

    // ── listApps ───────────────────────────────────────────
    @Test
    void listApps_withoutCategory_ordersByCreatedAtDesc() {
        when(appRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenReturn(List.of(availableApp(), availableApp()));

        List<Map<String, Object>> out = service.listApps(TENANT, null, 10);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsKeys("appKey", "name", "version", "status");
    }

    @Test
    void listApps_withCategory_usesCategoryQuery() {
        when(appRepository.findByTenantIdAndCategory(TENANT, "tools"))
                .thenReturn(List.of(availableApp()));

        List<Map<String, Object>> out = service.listApps(TENANT, "tools", 10);

        assertThat(out).hasSize(1);
        verify(appRepository).findByTenantIdAndCategory(TENANT, "tools");
    }

    @Test
    void listApps_respectsLimit() {
        when(appRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                .thenReturn(List.of(availableApp(), availableApp(), availableApp()));

        assertThat(service.listApps(TENANT, null, 2)).hasSize(2);
    }

    // ── getApp ─────────────────────────────────────────────
    @Test
    void getApp_found_returnsDto() {
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT))
                .thenReturn(Optional.of(availableApp()));

        Map<String, Object> dto = service.getApp(APP_KEY, TENANT);

        assertThat(dto.get("appKey")).isEqualTo(APP_KEY);
        assertThat(dto.get("name")).isEqualTo("演示插件");
    }

    @Test
    void getApp_missing_throws() {
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApp(APP_KEY, TENANT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("应用不存在");
    }

    // ── installApp ─────────────────────────────────────────
    @Test
    void installApp_success_marksInstalledAndRegisters() {
        PluginMarketplaceAppEntity app = availableApp();
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));
        UUID userId = UUID.randomUUID();

        Map<String, Object> dto = service.installApp(APP_KEY, TENANT, userId, Map.of("k", "v"));

        assertThat(dto.get("status")).isEqualTo(PluginMarketplaceAppEntity.Status.INSTALLED.name());
        assertThat(app.getInstalledBy()).isEqualTo(userId);
        assertThat(app.getInstalledAt()).isNotNull();
        verify(pluginRegistry).register(any(PluginManifest.class), eq(APP_KEY));
        verify(appRepository).save(app);
    }

    @Test
    void installApp_nullConfig_skipsConfigWrite() {
        PluginMarketplaceAppEntity app = availableApp();
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        Map<String, Object> dto = service.installApp(APP_KEY, TENANT, UUID.randomUUID(), null);

        assertThat(dto.get("status")).isEqualTo(PluginMarketplaceAppEntity.Status.INSTALLED.name());
    }

    @Test
    void installApp_alreadyInstalled_throws() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", "1.0.0", "name: x\n");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.installApp(APP_KEY, TENANT, UUID.randomUUID(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("应用已安装");
    }

    @Test
    void installApp_invalidManifest_marksFailedAndThrows() {
        // manifestYaml 为空 → PluginManifest.fromYaml 抛 PluginValidationException → catch 分支
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.AVAILABLE.name(), "1.0.0", "1.0.0", "");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.installApp(APP_KEY, TENANT, UUID.randomUUID(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("应用安装失败");

        // 失败后状态被标记为 INSTALL_FAILED 并记录错误
        assertThat(app.getStatus())
                .isEqualTo(PluginMarketplaceAppEntity.Status.INSTALL_FAILED.name());
        assertThat(app.getInstallError()).isNotNull();
    }

    @Test
    void installApp_registryThrows_marksFailedAndThrows() {
        PluginMarketplaceAppEntity app = availableApp();
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));
        doThrow(new RuntimeException("registry down"))
                .when(pluginRegistry).register(any(PluginManifest.class), anyString());

        assertThatThrownBy(() -> service.installApp(APP_KEY, TENANT, UUID.randomUUID(), null))
                .isInstanceOf(RuntimeException.class);

        assertThat(app.getStatus())
                .isEqualTo(PluginMarketplaceAppEntity.Status.INSTALL_FAILED.name());
    }

    // ── uninstallApp ───────────────────────────────────────
    @Test
    void uninstallApp_success_resetsFields() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", "1.0.0", "name: x\n");
        app.setInstalledBy(UUID.randomUUID());
        app.setInstalledAt(Instant.now());
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        service.uninstallApp(APP_KEY, TENANT);

        assertThat(app.getStatus()).isEqualTo(PluginMarketplaceAppEntity.Status.AVAILABLE.name());
        assertThat(app.getInstalledAt()).isNull();
        assertThat(app.getInstalledBy()).isNull();
        verify(pluginRegistry).unregister(APP_KEY);
        verify(appRepository).save(app);
    }

    @Test
    void uninstallApp_notInstalled_throws() {
        PluginMarketplaceAppEntity app = availableApp(); // AVAILABLE
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.uninstallApp(APP_KEY, TENANT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("应用未安装");
    }

    @Test
    void uninstallApp_missing_throws() {
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uninstallApp(APP_KEY, TENANT))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void uninstallApp_registryThrows_continues() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", "1.0.0", "name: x\n");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));
        doThrow(new RuntimeException("unregister failed")).when(pluginRegistry).unregister(APP_KEY);

        // registry 异常被 catch（仅 warn），卸载流程继续
        service.uninstallApp(APP_KEY, TENANT);

        assertThat(app.getStatus()).isEqualTo(PluginMarketplaceAppEntity.Status.AVAILABLE.name());
        verify(appRepository).save(app);
    }

    // ── updateConfig ───────────────────────────────────────
    @Test
    void updateConfig_success_serializesConfig() {
        PluginMarketplaceAppEntity app = availableApp();
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        Map<String, Object> dto = service.updateConfig(APP_KEY, TENANT, Map.of("enabled", true));

        assertThat(app.getConfigJson()).contains("enabled");
        assertThat(dto.get("appKey")).isEqualTo(APP_KEY);
        verify(appRepository).save(app);
    }

    @Test
    void updateConfig_missingApp_throws() {
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateConfig(APP_KEY, TENANT, Map.of()))
                .isInstanceOf(RuntimeException.class);
    }

    // ── checkUpdate ────────────────────────────────────────
    @Test
    void checkUpdate_newerVersionAvailable_hasUpdateTrue() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", "2.0.0", "name: x\n");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        Map<String, Object> out = service.checkUpdate(APP_KEY, TENANT);

        assertThat(out.get("hasUpdate")).isEqualTo(true);
        assertThat(out.get("currentVersion")).isEqualTo("1.0.0");
        assertThat(out.get("latestVersion")).isEqualTo("2.0.0");
    }

    @Test
    void checkUpdate_sameVersion_hasUpdateFalse() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", "1.0.0", "name: x\n");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        assertThat(service.checkUpdate(APP_KEY, TENANT).get("hasUpdate")).isEqualTo(false);
    }

    @Test
    void checkUpdate_nullLatestVersion_hasUpdateFalse() {
        PluginMarketplaceAppEntity app =
                appOf(PluginMarketplaceAppEntity.Status.INSTALLED.name(), "1.0.0", null, "name: x\n");
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.of(app));

        assertThat(service.checkUpdate(APP_KEY, TENANT).get("hasUpdate")).isEqualTo(false);
    }

    @Test
    void checkUpdate_missingApp_throws() {
        when(appRepository.findByAppKeyAndTenantId(APP_KEY, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkUpdate(APP_KEY, TENANT))
                .isInstanceOf(RuntimeException.class);
    }
}
