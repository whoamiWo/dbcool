package com.nocobase.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * R15: 插件 manifest 文件扫描器.
 *
 * 从指定目录扫描 *.yaml / *.yml 文件,逐个交给 Parser.
 * 扫描失败/解析失败的文件不会中断整体流程,只 warn.
 */
public final class PluginFileScanner {

    private static final Logger log = Logger.getLogger(PluginFileScanner.class.getName());

    private final Path pluginsDir;

    public PluginFileScanner(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    public PluginFileScanner() {
        this(defaultPluginsDir());
    }

    /** 默认插件目录:NOCOBASE_PLUGINS_DIR 环境变量 -> ./plugins. */
    public static Path defaultPluginsDir() {
        String env = System.getenv("NOCOBASE_PLUGINS_DIR");
        return Path.of(env != null && !env.isBlank() ? env : "./plugins");
    }

    /**
     * 扫描目录中所有 manifest 并注册到 registry.
     * @return 成功注册的 manifest 数
     */
    public int scanAndRegister(PluginRegistry registry) {
        if (!Files.isDirectory(pluginsDir)) {
            log.warning("插件目录不存在,跳过扫描: " + pluginsDir.toAbsolutePath());
            return 0;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            log.log(Level.SEVERE, "扫描插件目录失败: " + pluginsDir, e);
            return 0;
        }

        int loaded = 0;
        for (Path file : files) {
            try {
                String content = Files.readString(file);
                PluginManifest manifest = PluginManifest.fromYaml(content);
                registry.register(manifest, file.toString());
                loaded++;
            } catch (PluginValidationException | IOException e) {
                log.warning("跳过无效 manifest " + file + ": " + e.getMessage());
            }
        }
        log.info("插件扫描完成: dir=" + pluginsDir + " loaded=" + loaded + "/" + files.size());
        return loaded;
    }

    public Path getPluginsDir() {
        return pluginsDir;
    }
}
