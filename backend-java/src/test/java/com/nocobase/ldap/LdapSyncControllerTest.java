package com.nocobase.ldap;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.ldap.entity.LdapConfigEntity;
import com.nocobase.ldap.entity.LdapUserMappingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PHASE105: LdapSyncController 测试 (5 端点: create/toggle/sync/list/mappings)。 */
class LdapSyncControllerTest {

    private LdapSyncService ldapSyncService;
    private LdapSyncController controller;

    private static final String TENANT = "tenant_default";
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);

    @BeforeEach
    void setUp() {
        ldapSyncService = mock(LdapSyncService.class);
        controller = new LdapSyncController(ldapSyncService);
    }

    @Test
    void createConfig_returns201() {
        LdapConfigEntity cfg = new LdapConfigEntity();
        cfg.setId(UUID.randomUUID());
        cfg.setName("ad1");
        when(ldapSyncService.createConfig(
                eq("ad1"), eq("ldap://srv"), eq("dc=ex,dc=com"),
                eq("cn=bind"), eq("pw"), eq("(objectClass=user)"), eq("(objectClass=group)"),
                any(), eq(3600), eq(TENANT), eq(USER.userId())))
                .thenReturn(cfg);

        ResponseEntity<Map<String, Object>> resp = controller.createConfig(Map.of(
                "name", "ad1", "serverUrl", "ldap://srv", "baseDn", "dc=ex,dc=com",
                "bindDn", "cn=bind", "bindPassword", "pw",
                "userSearchFilter", "(objectClass=user)",
                "groupSearchFilter", "(objectClass=group)",
                "attributeMapping", Map.of("dn", "username"),
                "syncInterval", 3600
        ), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        assertThat(resp.getBody().get("data")).isEqualTo(cfg);
    }

    @Test
    void toggle_defaultEnabled_true() {
        LdapConfigEntity cfg = new LdapConfigEntity();
        cfg.setEnabled(true);
        when(ldapSyncService.toggleConfig(any(UUID.class), eq(true), eq(TENANT))).thenReturn(cfg);

        // 缺省 body → enabled=true
        ResponseEntity<Map<String, Object>> resp = controller.toggle(UUID.randomUUID(), Map.of(), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("enabled")).isEqualTo(true);
    }

    @Test
    void toggle_explicitFalse() {
        LdapConfigEntity cfg = new LdapConfigEntity();
        cfg.setEnabled(false);
        when(ldapSyncService.toggleConfig(any(UUID.class), eq(false), eq(TENANT))).thenReturn(cfg);

        ResponseEntity<Map<String, Object>> resp =
                controller.toggle(UUID.randomUUID(), Map.of("enabled", false), USER);

        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("enabled")).isEqualTo(false);
    }

    @Test
    void sync_returnsServiceResult() {
        UUID cfgId = UUID.randomUUID();
        when(ldapSyncService.syncUsers(cfgId, TENANT))
                .thenReturn(Map.of("created", 3, "updated", 1));

        ResponseEntity<Map<String, Object>> resp = controller.sync(cfgId, USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("created")).isEqualTo(3);
    }

    @Test
    void list_returnsConfigs() {
        when(ldapSyncService.listConfigs(TENANT)).thenReturn(List.of(new LdapConfigEntity()));

        ResponseEntity<Map<String, Object>> resp = controller.list(USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("configs")).isNotNull();
    }

    @Test
    void listMappings_returnsMappings() {
        when(ldapSyncService.listMappings(TENANT)).thenReturn(List.of(new LdapUserMappingEntity()));

        ResponseEntity<Map<String, Object>> resp =
                controller.listMappings(UUID.randomUUID(), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) resp.getBody().get("data")).get("mappings")).isNotNull();
    }
}
