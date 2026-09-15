package com.nocobase.plugin;

/**
 * R15: 插件生命周期钩子.
 *
 * 当插件被注册/重新注册/注销时,Registry 会回调对应方法,
 * 插件作者可在此清理资源、刷新缓存等.
 *
 * 注意:Manifest 本身不实现此接口,这是给"插件运行时"留的扩展点
 * (Week 44+ 接 ClassLoader / Bean 加载时使用).
 */
public interface PluginLifecycleHook {

    /** 插件被注册(新增)时调用. */
    default void onRegister(PluginManifest manifest) {}

    /** 插件被重新注册(覆盖/升级)时调用. */
    default void onReregister(PluginManifest oldManifest, PluginManifest newManifest) {}

    /** 插件被注销(删除/卸载)时调用;应释放资源. */
    default void onUnregister(PluginManifest manifest) {}
}
