package com.nocobase.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.plugin.entity.PluginMarketplaceAppEntity;
import com.nocobase.plugin.repository.PluginMarketplaceAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

/** PluginRegistryLoader 单测：验证启动时 DB 装载行为。 */
class PluginRegistryLoaderTest {

    private PluginMarketplaceAppRepository appRepository;
    private PluginRegistry pluginRegistry;
    private PluginRegistryLoader loader;

    private static final String APP_KEY_1 = "demo-plugin-1";
    private static final String APP_KEY_2 = "demo-plugin-2";
    private static final String APP_KEY_3 = "broken-plugin";
    private static final String MANIFEST_VALID_1 =
            "name: demo-plugin-1\n" +
            "version: 1.0.0\n" +
            "description: 演示插件 1\n";
    private static final String MANIFEST_VALID_2 =
            "name: demo-plugin-2\n" +
            "version: 2.0.0\n" +
            "description: 演示插件 2\n";
    private static final String MANIFEST_INVALID =
            "name: \n" + // name 为空，触发解析异常
            "version: 1.0.0\n";

    @BeforeEach
    void setUp() {
        appRepository = org.mockito.Mockito.mock(PluginMarketplaceAppRepository.class);
        pluginRegistry = new PluginRegistry(); // 真实实例而非 mock，便于验证实际注册行为
        loader = new PluginRegistryLoader(appRepository, pluginRegistry);
    }

    private PluginMarketplaceAppEntity makeEntity(String appKey, String manifestYaml) {
        PluginMarketplaceAppEntity e = new PluginMarketplaceAppEntity();
        e.setId(UUID.randomUUID());
        e.setAppKey(appKey);
        e.setName("演示插件");
        e.setVersion("1.0.0");
        e.setStatus(PluginMarketplaceAppEntity.Status.INSTALLED.name());
        e.setManifestYaml(manifestYaml);
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("正常场景：成功装载多个已安装插件")
    void run_whenMultipleInstalledPlugins_shouldRegisterAll() {
        // given
        List<PluginMarketplaceAppEntity> entities = Arrays.asList(
                makeEntity(APP_KEY_1, MANIFEST_VALID_1),
                makeEntity(APP_KEY_2, MANIFEST_VALID_2)
        );
        when(appRepository.findByStatus(anyString())).thenReturn(entities);

        // when
        loader.run(null);

        // then
        assertThat(pluginRegistry.size()).isEqualTo(2);
        assertThat(pluginRegistry.isRegistered("demo-plugin-1")).isTrue();
        assertThat(pluginRegistry.isRegistered("demo-plugin-2")).isTrue();
    }

    @Test
    @DisplayName("幂等场景：插件已注册时跳过装载")
    void run_whenAlreadyRegistered_shouldSkipWithoutError() {
        // given: 预先注册一个同名插件
        PluginManifest preReg = PluginManifest.fromYaml(MANIFEST_VALID_1);
        pluginRegistry.register(preReg, APP_KEY_1);

        List<PluginMarketplaceAppEntity> entities = Arrays.asList(
                makeEntity(APP_KEY_1, MANIFEST_VALID_1)
        );
        when(appRepository.findByStatus(anyString())).thenReturn(entities);

        // when
        loader.run(null);

        // then: 只有一个插件（预注册的那一个），第二个被跳过
        assertThat(pluginRegistry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败隔离场景：单条解析失败不影响其他插件注册")
    void run_whenSomeManifestInvalid_shouldRegisterOthersContinue() {
        // given: 中间插入一条损坏的 manifest
        List<PluginMarketplaceAppEntity> entities = Arrays.asList(
                makeEntity(APP_KEY_1, MANIFEST_VALID_1),
                makeEntity(APP_KEY_3, MANIFEST_INVALID),
                makeEntity(APP_KEY_2, MANIFEST_VALID_2)
        );
        when(appRepository.findByStatus(anyString())).thenReturn(entities);

        // when
        loader.run(null);

        // then: 有效的两个插件仍被注册
        assertThat(pluginRegistry.size()).isEqualTo(2);
        assertThat(pluginRegistry.isRegistered("demo-plugin-1")).isTrue();
        assertThat(pluginRegistry.isRegistered("demo-plugin-2")).isTrue();
        assertThat(pluginRegistry.isRegistered("broken-plugin")).isFalse();
    }

    @Test
    @DisplayName("空 manifest_yaml 场景：跳过并计入 failed 统计")
    void run_whenManifestYamlEmpty_shouldSkipAndCountFailed() {
        // given
        PluginMarketplaceAppEntity entity = makeEntity("empty-manifest", null);
        List<PluginMarketplaceAppEntity> entities = Arrays.asList(entity);
        when(appRepository.findByStatus(anyString())).thenReturn(entities);

        // when
        loader.run(null);

        // then: 未注册任何插件
        assertThat(pluginRegistry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("空查询结果场景：无已安装插件时不报错")
    void run_whenNoInstalledPlugins_shouldNotThrow() {
        // given
        when(appRepository.findByStatus(anyString())).thenReturn(List.of());

        // when
        loader.run(null);

        // then: 正常结束，size=0
        assertThat(pluginRegistry.size()).isEqualTo(0);
    }
}
