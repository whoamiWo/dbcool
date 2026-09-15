package com.nocobase.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R15: 插件注册表校验测试. */
class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @Test
    void registerValidManifest() {
        var m = new PluginManifest("test-plugin", "1.0.0", "A test", List.of("read", "write"), false);
        registry.register(m);
        assertEquals(1, registry.size());
        assertEquals("test-plugin", registry.registeredNames().get(0));
    }

    @Test
    void registerNullManifestThrows() {
        assertThrows(PluginValidationException.class, () -> registry.register(null));
    }

    @Test
    void registerDuplicateNameThrows() {
        var m1 = new PluginManifest("dup", "1.0", "first", List.of(), false);
        var m2 = new PluginManifest("dup", "2.0", "second", List.of(), false);
        registry.register(m1);
        assertThrows(PluginValidationException.class, () -> registry.register(m2));
    }

    @Test
    void blankNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginManifest("", "1.0", "desc", List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new PluginManifest(null, "1.0", "desc", List.of(), false));
    }

    @Test
    void blankVersionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginManifest("name", "", "desc", List.of(), false));
    }

    @Test
    void nullPermissionsDefaultsToEmpty() {
        var m = new PluginManifest("np", "1.0", "no perms", null, false);
        assertNotNull(m.permissions());
        assertTrue(m.permissions().isEmpty());
    }

    @Test
    void getReturnsManifest() {
        var m = new PluginManifest("g", "1.0", "", List.of(), false);
        registry.register(m);
        assertNotNull(registry.get("g"));
        assertEquals("g", registry.get("g").name());
    }

    @Test
    void getReturnsNullForUnknown() {
        assertNull(registry.get("unknown"));
    }

    @Test
    void collectPermissionsDeduplicates() {
        registry.register(new PluginManifest("p1", "1", "", List.of("read", "write"), false));
        registry.register(new PluginManifest("p2", "1", "", List.of("read", "delete"), false));
        var perms = registry.collectPermissions();
        assertEquals(3, perms.size()); // read, write, delete (read deduped)
    }

    @Test
    void isRegistered() {
        registry.register(new PluginManifest("ir", "1", "", List.of(), false));
        assertTrue(registry.isRegistered("ir"));
        assertFalse(registry.isRegistered("nope"));
    }

    @Test
    void clearResetsState() {
        registry.register(new PluginManifest("c", "1", "", List.of(), false));
        assertEquals(1, registry.size());
        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    void fromYamlParsesSimpleManifest() {
        String yaml = "name: yaml-plugin\nversion: 2.0.0\ndescription: from yaml\npermissions: read,write\n";
        var m = PluginManifest.fromYaml(yaml);
        assertEquals("yaml-plugin", m.name());
        assertEquals("2.0.0", m.version());
        assertEquals(2, m.permissions().size());
    }

    @Test
    void fromYamlThrowsOnInvalid() {
        // 缺少必填字段
        assertThrows(PluginValidationException.class,
                () -> PluginManifest.fromYaml("version: 1.0\n"));
    }

    @Test
    void snapshotIsUnmodifiable() {
        registry.register(new PluginManifest("s", "1", "", List.of(), false));
        var snap = registry.snapshot();
        assertThrows(UnsupportedOperationException.class, snap::clear);
    }

    // ── R15 增强: reregister / unregister / sourcePath ──────────

    @Test
    void registerWithSourcePath() {
        var m = new PluginManifest("sp", "1.0", "", List.of(), false);
        registry.register(m, "/tmp/sp.yaml");
        assertEquals("/tmp/sp.yaml", registry.getSourcePath("sp"));
    }

    @Test
    void reregisterOverwritesExisting() {
        var v1 = new PluginManifest("rr", "1.0", "first", List.of(), false);
        var v2 = new PluginManifest("rr", "2.0", "second", List.of(), false);
        registry.register(v1, "/tmp/rr.yaml");
        registry.reregister(v2);
        assertEquals(1, registry.size());
        assertEquals("2.0", registry.get("rr").version());
        assertEquals("second", registry.get("rr").description());
    }

    @Test
    void unregisterRemovesPlugin() {
        registry.register(new PluginManifest("un", "1.0", "", List.of(), false), "/tmp/un.yaml");
        registry.unregister("un");
        assertEquals(0, registry.size());
        assertNull(registry.get("un"));
        assertEquals("", registry.getSourcePath("un"));
    }

    @Test
    void unregisterUnknownIsNoOp() {
        registry.unregister("never-registered");  // 不抛错
        assertEquals(0, registry.size());
    }

    @Test
    void findNameBySourcePath() {
        registry.register(new PluginManifest("a", "1", "", List.of(), false), "/x/a.yaml");
        registry.register(new PluginManifest("b", "1", "", List.of(), false), "/x/b.yaml");
        assertEquals("a", registry.findNameBySourcePath("/x/a.yaml"));
        assertEquals("b", registry.findNameBySourcePath("/x/b.yaml"));
        assertNull(registry.findNameBySourcePath("/x/missing.yaml"));
    }

    @Test
    void clearAlsoRemovesSourcePaths() {
        registry.register(new PluginManifest("c", "1", "", List.of(), false), "/c.yaml");
        registry.clear();
        assertEquals("", registry.getSourcePath("c"));
    }

    // ── R15 增强: 生命周期钩子 ──────────────────────────────────

    static class RecordingHook implements PluginLifecycleHook {
        final java.util.List<String> events = new java.util.ArrayList<>();
        PluginManifest lastRegistered;
        PluginManifest lastUnregistered;
        PluginManifest lastOldReregister;
        PluginManifest lastNewReregister;

        @Override
        public void onRegister(PluginManifest m) {
            events.add("register:" + m.name());
            lastRegistered = m;
        }

        @Override
        public void onReregister(PluginManifest old, PluginManifest neu) {
            events.add("reregister:" + neu.name());
            lastOldReregister = old;
            lastNewReregister = neu;
        }

        @Override
        public void onUnregister(PluginManifest m) {
            events.add("unregister:" + m.name());
            lastUnregistered = m;
        }
    }

    @Test
    void registerTriggersOnRegister() {
        RecordingHook hook = new RecordingHook();
        registry.addLifecycleHook(hook);
        registry.register(new PluginManifest("h1", "1.0", "", List.of(), false));
        assertEquals(1, hook.events.size());
        assertEquals("register:h1", hook.events.get(0));
        assertEquals("h1", hook.lastRegistered.name());
    }

    @Test
    void reregisterTriggersOnReregister() {
        RecordingHook hook = new RecordingHook();
        registry.addLifecycleHook(hook);
        var v1 = new PluginManifest("h2", "1.0", "", List.of(), false);
        var v2 = new PluginManifest("h2", "2.0", "", List.of(), false);
        registry.register(v1);
        registry.reregister(v2);
        assertEquals(2, hook.events.size());
        assertEquals("reregister:h2", hook.events.get(1));
        assertEquals("1.0", hook.lastOldReregister.version());
        assertEquals("2.0", hook.lastNewReregister.version());
    }

    @Test
    void unregisterTriggersOnUnregister() {
        RecordingHook hook = new RecordingHook();
        registry.addLifecycleHook(hook);
        registry.register(new PluginManifest("h3", "1.0", "", List.of(), false));
        registry.unregister("h3");
        assertEquals(2, hook.events.size());
        assertEquals("unregister:h3", hook.events.get(1));
        assertEquals("h3", hook.lastUnregistered.name());
    }

    @Test
    void clearTriggersUnregisterForAll() {
        RecordingHook hook = new RecordingHook();
        registry.addLifecycleHook(hook);
        registry.register(new PluginManifest("c1", "1.0", "", List.of(), false));
        registry.register(new PluginManifest("c2", "1.0", "", List.of(), false));
        registry.clear();
        // register 2 次 + unregister 2 次
        assertEquals(4, hook.events.size());
        assertTrue(hook.events.contains("unregister:c1"));
        assertTrue(hook.events.contains("unregister:c2"));
    }

    @Test
    void removeHookStopsCallbacks() {
        RecordingHook hook = new RecordingHook();
        registry.addLifecycleHook(hook);
        registry.removeLifecycleHook(hook);
        registry.register(new PluginManifest("h4", "1.0", "", List.of(), false));
        assertEquals(0, hook.events.size());
    }

    @Test
    void failingHookDoesNotBreakOthers() {
        RecordingHook good = new RecordingHook();
        PluginLifecycleHook bad = new PluginLifecycleHook() {
            @Override
            public void onRegister(PluginManifest m) {
                throw new RuntimeException("boom");
            }
        };
        registry.addLifecycleHook(bad);
        registry.addLifecycleHook(good);
        // register 不应被异常中断
        registry.register(new PluginManifest("h5", "1.0", "", List.of(), false));
        assertEquals(1, good.events.size());
    }

    @Test
    void hookCountReflectsRegistrations() {
        assertEquals(0, registry.hookCount());
        RecordingHook h1 = new RecordingHook();
        RecordingHook h2 = new RecordingHook();
        registry.addLifecycleHook(h1);
        registry.addLifecycleHook(h2);
        assertEquals(2, registry.hookCount());
        registry.removeLifecycleHook(h1);
        assertEquals(1, registry.hookCount());
    }
}
