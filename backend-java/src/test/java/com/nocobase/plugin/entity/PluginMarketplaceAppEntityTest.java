package com.nocobase.plugin.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * PHASE105: plugin.entity 包覆盖（PluginMarketplaceAppEntity）。
 */
public class PluginMarketplaceAppEntityTest {

    @Test
    void allFields_gettersAndSetters() {
        PluginMarketplaceAppEntity e = new PluginMarketplaceAppEntity();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        UUID user = UUID.randomUUID();

        e.setId(id);
        e.setAppKey("app-1");
        e.setName("插件A");
        e.setVersion("1.0.0");
        e.setLatestVersion("1.2.0");
        e.setDescription("desc");
        e.setAuthor("dev");
        e.setIconUrl("http://icon");
        e.setHomepageUrl("http://home");
        e.setCategory("tools");
        e.setTagsJson("[\"a\"]");
        e.setPermissionsJson("[\"p\"]");
        e.setManifestYaml("yaml");
        e.setConfigSchemaJson("{}");
        e.setStatus("INSTALLED");
        e.setInstallError("err");
        e.setTenantId("tenant_default");
        e.setConfigJson("{}");
        e.setInstalledBy(user);
        e.setInstalledAt(now);
        e.setUpdatedAt(now);
        e.setCreatedAt(now);

        assertEquals(id, e.getId());
        assertEquals("app-1", e.getAppKey());
        assertEquals("插件A", e.getName());
        assertEquals("1.0.0", e.getVersion());
        assertEquals("1.2.0", e.getLatestVersion());
        assertEquals("desc", e.getDescription());
        assertEquals("dev", e.getAuthor());
        assertEquals("http://icon", e.getIconUrl());
        assertEquals("http://home", e.getHomepageUrl());
        assertEquals("tools", e.getCategory());
        assertEquals("[\"a\"]", e.getTagsJson());
        assertEquals("[\"p\"]", e.getPermissionsJson());
        assertEquals("yaml", e.getManifestYaml());
        assertEquals("{}", e.getConfigSchemaJson());
        assertEquals("INSTALLED", e.getStatus());
        assertEquals("err", e.getInstallError());
        assertEquals("tenant_default", e.getTenantId());
        assertEquals("{}", e.getConfigJson());
        assertEquals(user, e.getInstalledBy());
        assertEquals(now, e.getInstalledAt());
        assertEquals(now, e.getUpdatedAt());
        assertEquals(now, e.getCreatedAt());
    }

    @Test
    void defaults_areApplied() {
        PluginMarketplaceAppEntity e = new PluginMarketplaceAppEntity();
        assertEquals("AVAILABLE", e.getStatus());
        assertEquals("[]", e.getTagsJson());
        assertEquals("[]", e.getPermissionsJson());
        assertEquals("{}", e.getConfigSchemaJson());
        assertEquals("{}", e.getConfigJson());
        assertNotNull(e.getCreatedAt());
    }

    @Test
    void statusEnum_values() {
        assertArrayEquals(
                new Object[]{
                        PluginMarketplaceAppEntity.Status.AVAILABLE,
                        PluginMarketplaceAppEntity.Status.INSTALLED,
                        PluginMarketplaceAppEntity.Status.PENDING_INSTALL,
                        PluginMarketplaceAppEntity.Status.INSTALL_FAILED,
                        PluginMarketplaceAppEntity.Status.UPDATING
                },
                PluginMarketplaceAppEntity.Status.values()
        );
    }
}
