package com.nocobase.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * R15: 插件热重载器(基于 NIO.2 WatchService).
 *
 * 监听 plugins_dir 的 *.yaml / *.yml 文件变化,
 * CREATE / MODIFY → 重新解析并注册到 Registry;
 * DELETE → 从 Registry 注销.
 *
 * 设计:
 * - 启动时一次性扫描,然后启动后台 watcher 线程
 * - 使用 daemon 线程, JVM 退出时不阻塞
 * - 防抖:同文件 500ms 内多次事件合并为一次(简单 lock + last_event_ts)
 *
 * Usage:
 *     PluginRegistry registry = ...;
 *     PluginHotReloader reloader = new PluginHotReloader(pluginsDir, registry);
 *     reloader.start();
 *     ...
 *     reloader.stop();
 */
public final class PluginHotReloader implements AutoCloseable {

    private static final Logger log = Logger.getLogger(PluginHotReloader.class.getName());

    private final Path pluginsDir;
    private final PluginRegistry registry;
    private final PluginFileScanner scanner;
    private final Object lock = new Object();
    private long lastEventTs = 0L;
    private static final long DEBOUNCE_MS = 500;

    private WatchService watchService;
    private Thread watcherThread;
    private volatile boolean running = false;

    public PluginHotReloader(Path pluginsDir, PluginRegistry registry) {
        this.pluginsDir = pluginsDir;
        this.registry = registry;
        this.scanner = new PluginFileScanner(pluginsDir);
    }

    /** 启动后台 watcher 线程. */
    public void start() throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            log.warning("插件目录不存在,跳过热重载监听: " + pluginsDir.toAbsolutePath());
            return;
        }
        // 首次全量扫描
        scanner.scanAndRegister(registry);
        // 启动 WatchService
        watchService = pluginsDir.getFileSystem().newWatchService();
        pluginsDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        running = true;
        watcherThread = new Thread(this::watchLoop, "plugin-hot-reloader");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("插件热重载已启动: " + pluginsDir.toAbsolutePath());
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                @SuppressWarnings("unchecked")
                Path filename = ((WatchEvent<Path>) event).context();
                if (!isYaml(filename)) continue;
                Path file = pluginsDir.resolve(filename);
                handleEvent(kind, file);
            }
            if (!key.reset()) {
                log.warning("WatchKey 失效,停止监听: " + pluginsDir);
                return;
            }
        }
    }

    private boolean isYaml(Path filename) {
        String n = filename.getFileName().toString().toLowerCase();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    private void handleEvent(WatchEvent.Kind<?> kind, Path file) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now - lastEventTs < DEBOUNCE_MS) {
                return;  // 防抖:同 500ms 内合并
            }
            lastEventTs = now;
        }
        try {
            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                String name = registry.findNameBySourcePath(file.toString());
                if (name != null) {
                    registry.unregister(name);
                    log.info("插件已注销(删除): " + name);
                }
            } else {
                // CREATE / MODIFY: 解析并注册(去重由 registry 自身保证)
                try {
                    String content = Files.readString(file);
                    PluginManifest manifest = PluginManifest.fromYaml(content);
                    if (registry.isRegistered(manifest.name())) {
                        registry.reregister(manifest, file.toString());
                        log.info("插件已更新: " + manifest.name() + " v" + manifest.version());
                    } else {
                        registry.register(manifest, file.toString());
                        log.info("插件已注册(新增): " + manifest.name());
                    }
                } catch (PluginValidationException e) {
                    log.warning("跳过无效 manifest " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "处理插件事件失败: " + file, e);
        }
    }

    public void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.log(Level.WARNING, "关闭 WatchService 失败", e);
            }
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        log.info("插件热重载已停止");
    }

    @Override
    public void close() {
        stop();
    }
}
