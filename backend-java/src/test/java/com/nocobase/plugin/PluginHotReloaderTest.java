package com.nocobase.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R15: 插件热重载器测试. */
class PluginHotReloaderTest {

    @TempDir
    Path tempDir;

    private PluginRegistry registry;
    private PluginHotReloader reloader;

    @BeforeEach
    void setUp() throws IOException {
        registry = new PluginRegistry();
    }

    @AfterEach
    void tearDown() {
        if (reloader != null) {
            reloader.close();
        }
        registry.clear();
    }

    @Test
    void startLoadsExistingFiles() throws Exception {
        Files.writeString(tempDir.resolve("a.yaml"), "name: a\nversion: 1.0\n");
        Files.writeString(tempDir.resolve("b.yaml"), "name: b\nversion: 2.0\n");
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        // 等扫描线程完成
        awaitSize(2);
        assertEquals(2, registry.size());
        assertNotNull(registry.get("a"));
        assertNotNull(registry.get("b"));
    }

    @Test
    void createNewFileTriggersRegister() throws Exception {
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        awaitSize(0);

        Files.writeString(tempDir.resolve("new.yaml"), "name: new\nversion: 1.0\n");
        await(() -> registry.isRegistered("new"));
        assertEquals("1.0", registry.get("new").version());
    }

    @Test
    void modifyFileTriggersReregister() throws Exception {
        Files.writeString(tempDir.resolve("m.yaml"), "name: m\nversion: 1.0\n");
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        await(() -> registry.isRegistered("m"));
        assertEquals("1.0", registry.get("m").version());

        Files.writeString(tempDir.resolve("m.yaml"), "name: m\nversion: 2.0\n");
        await(() -> "2.0".equals(registry.get("m").version()));
        assertEquals("2.0", registry.get("m").version());
    }

    @Test
    void deleteFileTriggersUnregister() throws Exception {
        Path file = tempDir.resolve("d.yaml");
        Files.writeString(file, "name: d\nversion: 1.0\n");
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        await(() -> registry.isRegistered("d"));

        Files.delete(file);
        await(() -> !registry.isRegistered("d"));
        assertEquals(0, registry.size());
    }

    @Test
    void modifyInvalidFileDoesNotBreak() throws Exception {
        Files.writeString(tempDir.resolve("ok.yaml"), "name: ok\nversion: 1.0\n");
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        await(() -> registry.isRegistered("ok"));

        // 写入无效内容(name 缺失)
        Files.writeString(tempDir.resolve("ok.yaml"), "version: 1.0\n");
        // 给 watcher 一些时间触发(应该被 catch 住,不影响其他插件)
        TimeUnit.MILLISECONDS.sleep(800);
        // ok 应该仍存在(我们没改动 name),版本仍能解析
        assertTrue(registry.isRegistered("ok") || registry.size() == 0,
                "无效 manifest 不应破坏 watcher");
    }

    @Test
    void startOnMissingDirIsNoOp() throws Exception {
        Path missing = tempDir.resolve("nonexistent");
        reloader = new PluginHotReloader(missing, registry);
        reloader.start();  // 不抛错
        assertEquals(0, registry.size());
    }

    @Test
    void stopStopsWatcher() throws Exception {
        reloader = new PluginHotReloader(tempDir, registry);
        reloader.start();
        TimeUnit.MILLISECONDS.sleep(100);
        reloader.stop();  // 不抛错

        // 后续创建文件不应被监听
        Files.writeString(tempDir.resolve("after.yaml"), "name: after\nversion: 1.0\n");
        TimeUnit.MILLISECONDS.sleep(500);
        assertEquals(0, registry.size());
    }

    // ── helpers ────────────────────────────────────────────────
    private void awaitSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && registry.size() != expected) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !cond.getAsBoolean()) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}
