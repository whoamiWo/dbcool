package com.nocobase.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nocobase.plugin.entity.PluginMarketplaceAppEntity;
import com.nocobase.plugin.repository.PluginMarketplaceAppRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 插件市场服务 — 应用商店 API + 安装流程。
 *
 * <p>功能:
 * <ul>
 *   <li>浏览可用应用列表</li>
 *   <li>安装/升级/卸载应用</li>
 *   <li>管理应用配置</li>
 *   <li>检查更新</li>
 * </ul>
 */
@Service
public class PluginMarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(PluginMarketplaceService.class);

    private final PluginMarketplaceAppRepository appRepository;
    private final PluginRegistry pluginRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public PluginMarketplaceService(
            PluginMarketplaceAppRepository appRepository,
            PluginRegistry pluginRegistry
    ) {
        this.appRepository = appRepository;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 浏览可用应用列表。
     */
    public List<Map<String, Object>> listApps(String tenantId, String category, int limit) {
        List<PluginMarketplaceAppEntity> apps;
        if (category != null && !category.isBlank()) {
            apps = appRepository.findByTenantIdAndCategory(tenantId, category);
        } else {
            apps = appRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (PluginMarketplaceAppEntity app : apps) {
            if (result.size() >= limit) break;
            result.add(toDto(app));
        }
        return result;
    }

    /**
     * 获取应用详情。
     */
    public Map<String, Object> getApp(String appKey, String tenantId) {
        PluginMarketplaceAppEntity app = appRepository.findByAppKeyAndTenantId(appKey, tenantId)
                .orElseThrow(() -> new RuntimeException("应用不存在: " + appKey));
        return toDto(app);
    }

    /**
     * 安装应用。
     */
    @Transactional
    public Map<String, Object> installApp(String appKey, String tenantId, UUID userId, Map<String, Object> config) {
        PluginMarketplaceAppEntity app = appRepository.findByAppKeyAndTenantId(appKey, tenantId)
                .orElseThrow(() -> new RuntimeException("应用不存在: " + appKey));

        if (PluginMarketplaceAppEntity.Status.INSTALLED.name().equals(app.getStatus())) {
            throw new RuntimeException("应用已安装");
        }

        try {
            app.setStatus(PluginMarketplaceAppEntity.Status.PENDING_INSTALL.name());
            app.setUpdatedAt(Instant.now());
            app.setInstalledBy(userId);

            PluginManifest manifest = PluginManifest.fromYaml(app.getManifestYaml());
            pluginRegistry.register(manifest, app.getAppKey());

            if (config != null) {
                app.setConfigJson(objectMapper.writeValueAsString(config));
            }

            app.setStatus(PluginMarketplaceAppEntity.Status.INSTALLED.name());
            app.setInstalledAt(Instant.now());
            appRepository.save(app);

            log.info("[marketplace] 应用安装成功: {} v{}", appKey, app.getVersion());

            return toDto(app);
        } catch (Exception e) {
            app.setStatus(PluginMarketplaceAppEntity.Status.INSTALL_FAILED.name());
            app.setInstallError(e.getMessage());
            appRepository.save(app);
            throw new RuntimeException("应用安装失败: " + e.getMessage(), e);
        }
    }

    /**
     * 卸载应用。
     */
    @Transactional
    public void uninstallApp(String appKey, String tenantId) {
        PluginMarketplaceAppEntity app = appRepository.findByAppKeyAndTenantId(appKey, tenantId)
                .orElseThrow(() -> new RuntimeException("应用不存在: " + appKey));

        if (!PluginMarketplaceAppEntity.Status.INSTALLED.name().equals(app.getStatus())) {
            throw new RuntimeException("应用未安装");
        }

        try {
            pluginRegistry.unregister(appKey);
        } catch (Exception e) {
            log.warn("卸载插件时出错: {}", e.getMessage());
        }

        app.setStatus(PluginMarketplaceAppEntity.Status.AVAILABLE.name());
        app.setInstalledAt(null);
        app.setInstalledBy(null);
        app.setUpdatedAt(Instant.now());
        appRepository.save(app);

        log.info("[marketplace] 应用卸载: {}", appKey);
    }

    /**
     * 更新应用配置。
     */
    @Transactional
    public Map<String, Object> updateConfig(String appKey, String tenantId, Map<String, Object> config) {
        PluginMarketplaceAppEntity app = appRepository.findByAppKeyAndTenantId(appKey, tenantId)
                .orElseThrow(() -> new RuntimeException("应用不存在: " + appKey));

        app.setConfigJson(objectMapper.writeValueAsString(config));
        app.setUpdatedAt(Instant.now());
        appRepository.save(app);

        return toDto(app);
    }

    /**
     * 检查更新。
     */
    public Map<String, Object> checkUpdate(String appKey, String tenantId) {
        PluginMarketplaceAppEntity app = appRepository.findByAppKeyAndTenantId(appKey, tenantId)
                .orElseThrow(() -> new RuntimeException("应用不存在: " + appKey));

        boolean hasUpdate = app.getLatestVersion() != null
                && !app.getVersion().equals(app.getLatestVersion());

        Map<String, Object> result = new HashMap<>();
        result.put("appKey", appKey);
        result.put("currentVersion", app.getVersion());
        result.put("latestVersion", app.getLatestVersion());
        result.put("hasUpdate", hasUpdate);
        return result;
    }

    /**
     * 列出租户已安装的应用。
     */
    public List<Map<String, Object>> listInstalledApps(String tenantId) {
        List<PluginMarketplaceAppEntity> apps = appRepository
                .findByTenantIdAndStatus(tenantId, PluginMarketplaceAppEntity.Status.INSTALLED.name());
        List<Map<String, Object>> result = new ArrayList<>();
        for (PluginMarketplaceAppEntity app : apps) {
            result.add(toDto(app));
        }
        return result;
    }

    // ============================================================
    //  内部方法
    // ============================================================

    private Map<String, Object> toDto(PluginMarketplaceAppEntity app) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", app.getId().toString());
        dto.put("appKey", app.getAppKey());
        dto.put("name", app.getName());
        dto.put("version", app.getVersion());
        dto.put("latestVersion", app.getLatestVersion());
        dto.put("description", app.getDescription());
        dto.put("author", app.getAuthor());
        dto.put("iconUrl", app.getIconUrl());
        dto.put("category", app.getCategory());
        dto.put("tags", parseJsonArray(app.getTagsJson()));
        dto.put("permissions", parseJsonArray(app.getPermissionsJson()));
        dto.put("status", app.getStatus());
        dto.put("installError", app.getInstallError());
        dto.put("config", parseJsonObject(app.getConfigJson()));
        dto.put("installedAt", app.getInstalledAt());
        dto.put("createdAt", app.getCreatedAt());
        return dto;
    }

    private List<Object> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Object.class));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseJsonObject(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}