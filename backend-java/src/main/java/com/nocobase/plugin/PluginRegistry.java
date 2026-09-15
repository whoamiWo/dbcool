package com.nocobase.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;

/**
 * R15: 插件注册表 + 加载时校验.
 * 功能:
 * - 注册插件 manifest 并校验必填字段
 * - 去重(同名插件拒绝第二次注册)
 * - 权限收集
 * - 所有已注册插件的快照查询
 */
@Component
public class PluginRegistry {

    private static final Logger log = Logger.getLogger(PluginRegistry.class.getName());

    private final Map<String, PluginManifest> plugins = new LinkedHashMap<>();
    /** 每个插件对应的源文件路径(可选,用于热重载 delete 匹配). */
    private final Map<String, String> sourcePaths = new LinkedHashMap<>();
    /** 全局生命周期钩子. */
    private final List<PluginLifecycleHook> hooks = new CopyOnWriteArrayList<>();

    /** 注册全局生命周期钩子. */
    public void addLifecycleHook(PluginLifecycleHook hook) {
        if (hook != null) {
            hooks.add(hook);
            log.info("[plugin-registry] 注册生命周期钩子: " + hook.getClass().getSimpleName());
        }
    }

    public void removeLifecycleHook(PluginLifecycleHook hook) {
        hooks.remove(hook);
    }

    /** 注册插件 manifest;去重 + 校验;重复名称抛 PluginValidationException. */
    public void register(PluginManifest manifest) {
        register(manifest, "");
    }

    /** 注册插件 manifest 并指定源文件路径;用于热重载 delete 匹配. */
    public void register(PluginManifest manifest, String sourcePath) {
        if (manifest == null) {
            throw new PluginValidationException("manifest 不能为 null");
        }
        String name = manifest.name();
        if (name == null || name.isBlank()) {
            throw new PluginValidationException("name 必填");
        }
        if (plugins.containsKey(name)) {
            throw new PluginValidationException("插件 '" + name + "' 已注册,不可重复");
        }
        plugins.put(name, manifest);
        sourcePaths.put(name, sourcePath == null ? "" : sourcePath);
        log.info("[plugin-registry] 注册插件: " + name + " v" + manifest.version());
        fireOnRegister(manifest);
    }

    /** 重新注册(覆盖)同名插件;用于热重载 modify. */
    public void reregister(PluginManifest manifest) {
        reregister(manifest, sourcePaths.getOrDefault(manifest.name(), ""));
    }

    public void reregister(PluginManifest manifest, String sourcePath) {
        if (manifest == null) {
            throw new PluginValidationException("manifest 不能为 null");
        }
        String name = manifest.name();
        if (name == null || name.isBlank()) {
            throw new PluginValidationException("name 必填");
        }
        PluginManifest old = plugins.get(name);
        plugins.put(name, manifest);
        sourcePaths.put(name, sourcePath == null ? "" : sourcePath);
        log.info("[plugin-registry] 更新插件: " + name + " v" + manifest.version());
        fireOnReregister(old, manifest);
    }

    /** 注销插件;不存在则忽略. */
    public void unregister(String name) {
        if (name == null) return;
        PluginManifest removed = plugins.remove(name);
        if (removed != null) {
            sourcePaths.remove(name);
            log.info("[plugin-registry] 注销插件: " + name);
            fireOnUnregister(removed);
        }
    }

    private void fireOnRegister(PluginManifest m) {
        for (PluginLifecycleHook h : hooks) {
            try {
                h.onRegister(m);
            } catch (Exception e) {
                log.log(Level.WARNING, "hook onRegister 失败: " + h.getClass().getSimpleName(), e);
            }
        }
    }

    private void fireOnReregister(PluginManifest old, PluginManifest neu) {
        for (PluginLifecycleHook h : hooks) {
            try {
                h.onReregister(old, neu);
            } catch (Exception e) {
                log.log(Level.WARNING, "hook onReregister 失败: " + h.getClass().getSimpleName(), e);
            }
        }
    }

    private void fireOnUnregister(PluginManifest m) {
        for (PluginLifecycleHook h : hooks) {
            try {
                h.onUnregister(m);
            } catch (Exception e) {
                log.log(Level.WARNING, "hook onUnregister 失败: " + h.getClass().getSimpleName(), e);
            }
        }
    }

    /** 当前已注册的钩子数量(供监控). */
    public int hookCount() {
        return hooks.size();
    }

    /** 获取插件的源文件路径(空字符串表示未设置). */
    public String getSourcePath(String name) {
        return sourcePaths.getOrDefault(name, "");
    }

    /** 根据源文件路径查找插件名(用于热重载 DELETE 事件). */
    public String findNameBySourcePath(String path) {
        if (path == null) return null;
        for (var e : sourcePaths.entrySet()) {
            if (path.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** 根据名称获取 manifest;不存在返回 null. */
    public PluginManifest get(String name) {
        return plugins.get(name);
    }

    /** 检查某名称是否已注册. */
    public boolean isRegistered(String name) {
        return plugins.containsKey(name);
    }

    /** 已注册插件数量. */
    public int size() {
        return plugins.size();
    }

    /** 所有已注册插件的名称列表. */
    public List<String> registeredNames() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.keySet()));
    }

    /** 所有已注册插件的快照. */
    public Map<String, PluginManifest> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(plugins));
    }

    /** 收集所有已注册插件声明的权限(去重). */
    public Collection<String> collectPermissions() {
        var perms = new java.util.LinkedHashSet<String>();
        for (PluginManifest m : plugins.values()) {
            if (m.permissions() != null) {
                perms.addAll(m.permissions());
            }
        }
        return Collections.unmodifiableSet(perms);
    }

    /** 清空(用于测试).会先对每个插件触发 onUnregister. */
    public void clear() {
        for (PluginManifest m : plugins.values()) {
            fireOnUnregister(m);
        }
        plugins.clear();
        sourcePaths.clear();
    }
}
