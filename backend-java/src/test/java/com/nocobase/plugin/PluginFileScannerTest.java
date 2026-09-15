package com.nocobase.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginFileScannerTest {

    @TempDir
    Path tempDir;

    private PluginRegistry registry;
    private PluginFileScanner scanner;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
        scanner = new PluginFileScanner(tempDir);
    }

    @Test
    void scanEmptyDirReturnsZero() {
        int n = scanner.scanAndRegister(registry);
        assertEquals(0, n);
        assertEquals(0, registry.size());
    }

    @Test
    void scanMissingDirReturnsZero() {
        PluginFileScanner s = new PluginFileScanner(tempDir.resolve("nope"));
        int n = s.scanAndRegister(registry);
        assertEquals(0, n);
    }

    @Test
    void scanLoadsValidYaml() throws IOException {
        Files.writeString(tempDir.resolve("hello.yaml"), "name: hello\nversion: 1.0.0\ndescription: hello plugin\n");
        int n = scanner.scanAndRegister(registry);
        assertEquals(1, n);
        PluginManifest m = registry.get("hello");
        assertNotNull(m);
        assertEquals("1.0.0", m.version());
    }

    @Test
    void scanLoadsValidYml() throws IOException {
        Files.writeString(tempDir.resolve("world.yml"), "name: world\nversion: 2.0\n");
        int n = scanner.scanAndRegister(registry);
        assertEquals(1, n);
    }

    @Test
    void scanSkipsInvalidFile() throws IOException {
        Files.writeString(tempDir.resolve("bad.yaml"), "version: 0.1\n");
        Files.writeString(tempDir.resolve("good.yaml"), "name: good\nversion: 1.0\n");
        int n = scanner.scanAndRegister(registry);
        assertEquals(1, n);
        assertTrue(registry.isRegistered("good"));
    }

    @Test
    void scanIgnoresNonYamlFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# readme");
        Files.writeString(tempDir.resolve("plugin.txt"), "ignored");
        Files.writeString(tempDir.resolve("real.yaml"), "name: real\nversion: 1.0\n");
        int n = scanner.scanAndRegister(registry);
        assertEquals(1, n);
    }

    @Test
    void scanLoadsMultiplePlugins() throws IOException {
        Files.writeString(tempDir.resolve("a.yaml"), "name: a\nversion: 1\n");
        Files.writeString(tempDir.resolve("b.yaml"), "name: b\nversion: 2\n");
        Files.writeString(tempDir.resolve("c.yml"), "name: c\nversion: 3\n");
        int n = scanner.scanAndRegister(registry);
        assertEquals(3, n);
        assertEquals(3, registry.size());
    }

    @Test
    void scanCollectsPermissions() throws IOException {
        String yaml = "name: p\nversion: 1.0\npermissions:\n  - read\n  - write\n  - delete\nrequiresAuth: true\n";
        Files.writeString(tempDir.resolve("p.yaml"), yaml);
        scanner.scanAndRegister(registry);
        PluginManifest m = registry.get("p");
        assertEquals(List.of("read", "write", "delete"), m.permissions());
        assertTrue(m.requiresAuth());
    }

    @Test
    void getPluginsDirReturnsConfiguredPath() {
        Path p = tempDir.resolve("sub");
        PluginFileScanner s = new PluginFileScanner(p);
        assertEquals(p, s.getPluginsDir());
    }
}
