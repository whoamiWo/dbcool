package com.nocobase.plugin;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.nocobase.plugin.entity.PluginMarketplaceAppEntity;
import com.nocobase.plugin.repository.PluginMarketplaceAppRepository;

/**
 * 启动时把 DB 中已安装的插件重新注册到内存注册表（避免重启后注册表丢失）。
 *
 * <p>设计要点：
 * <ul>
 * <li>使用 {@code @Order(Integer.MAX_VALUE)} 保证在所有依赖 Registry 的业务启动完成后才执行，
 *     避免竞态（例如 PluginFileScanner / FileWatcher 可能同时注册同名插件，此处幂等跳过）。</li>
 * <li>每条记录独立 try/catch，单条解析失败不影响其他插件注册。</li>
 * <li>若 manifest 已注册（{@code isRegistered}），视为幂等，跳过且不打印冗余日志。</li>
 * </ul>
 */
@Component
@Order(Integer.MAX_VALUE)
public class PluginRegistryLoader implements ApplicationRunner {

    private static final Logger log = Logger.getLogger(PluginRegistryLoader.class.getName());

    private final PluginMarketplaceAppRepository appRepository;
    private final PluginRegistry pluginRegistry;

    public PluginRegistryLoader(PluginMarketplaceAppRepository appRepository, PluginRegistry pluginRegistry) {
        this.appRepository = appRepository;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        int installed = 0;
        int registered = 0;
        int skipped = 0;
        int failed = 0;

        try {
            List<PluginMarketplaceAppEntity> entities =
                    appRepository.findByStatus(PluginMarketplaceAppEntity.Status.INSTALLED.name());
            for (PluginMarketplaceAppEntity entity : entities) {
                installed++;
                if (entity.getManifestYaml() == null || entity.getManifestYaml().isBlank()) {
                    log.warning("[plugin-registry] 启动装载 跳过: " + entity.getAppKey()
                            + " manifest_yaml 为空");
                    failed++;
                    continue;
                }
                try {
                    PluginManifest manifest = PluginManifest.fromYaml(entity.getManifestYaml());
                    if (pluginRegistry.isRegistered(manifest.name())) {
                        // 注册表以插件名为全局唯一键；多租户装同名插件时后到者不生效，
                        // 必须显式告警（而非静默丢弃），便于运维定位"已安装却不生效"。
                        log.warning("[plugin-registry] 启动装载 同名冲突跳过: plugin=" + manifest.name()
                                + " 已由其他租户装载，跳过 tenant=" + entity.getTenantId()
                                + " appKey=" + entity.getAppKey());
                        skipped++;
                        continue;
                    }
                    // sourcePath 传空串：appKey 不是文件路径，写入会污染
                    // PluginRegistry#findNameBySourcePath 的热重载删除匹配语义。
                    pluginRegistry.register(manifest, "");
                    registered++;
                } catch (Exception e) {
                    log.log(Level.WARNING,
                            "[plugin-registry] 启动装载 解析失败: appKey=" + entity.getAppKey(), e);
                    failed++;
                }
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "[plugin-registry] 启动装载 查询异常", e);
        }

        log.info(String.format("[plugin-registry] 启动装载完成 installed=%d registered=%d skipped=%d failed=%d",
                installed, registered, skipped, failed));
    }
}
