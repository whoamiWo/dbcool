package com.nocobase.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.ldap.entity.LdapConfigEntity;
import com.nocobase.ldap.entity.LdapUserMappingEntity;
import com.nocobase.ldap.repository.LdapConfigRepository;
import com.nocobase.ldap.repository.LdapUserMappingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * PHASE96 T6: LDAP 同步用户口令与定时同步范围。
 *
 * <p>两条验收：
 * <ul>
 *   <li>同步创建的本地用户不得持有可预测的占位口令（此前全库写死 "LDAP_SYNCED"）</li>
 *   <li>定时同步遍历所有租户的启用配置，不再硬编码 system 租户</li>
 * </ul>
 */
class LdapSyncServiceTest {

    private LdapConfigRepository configRepository;
    private LdapUserMappingRepository mappingRepository;
    private UserRepository userRepository;
    private LdapTemplate ldapTemplate;
    private LdapSyncService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(LdapConfigRepository.class);
        mappingRepository = mock(LdapUserMappingRepository.class);
        userRepository = mock(UserRepository.class);
        ldapTemplate = mock(LdapTemplate.class);
        service = new LdapSyncService(
                configRepository, mappingRepository, userRepository, ldapTemplate,
                new BCryptPasswordEncoder());
    }

    private LdapConfigEntity config(String tenantId, String name) {
        LdapConfigEntity c = new LdapConfigEntity();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenantId);
        c.setName(name);
        c.setEnabled(true);
        return c;
    }

    /** 同步创建的本地用户口令必须是随机不可登录值，不能是可预测的 "LDAP_SYNCED"。 */
    @Test
    void syncUser_passwordIsRandom_notPlaceholder() {
        LdapConfigEntity cfg = config("tenant_a", "cfg-a");
        when(mappingRepository.findByLdapUidAndTenantId(eq("uid-1"), eq("tenant_a")))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername("uid-1")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncUser(cfg, Map.of(
                "uid", "uid-1",
                "dn", "cn=uid-1,dc=example,dc=com",
                "displayName", "UID One"), "tenant_a");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        String hash = captor.getValue().getPasswordHash();
        // 不是已知占位串、不是空、且是 bcrypt 哈希（不可逆）
        assertThat(hash).isNotEqualTo("LDAP_SYNCED").isNotBlank().startsWith("$2");
    }

    /** 两次同步不同用户 → 口令不同（证明是随机而非固定值）。 */
    @Test
    void syncUser_twoUsers_haveDifferentPasswords() {
        when(mappingRepository.findByLdapUidAndTenantId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LdapConfigEntity cfg = config("tenant_a", "cfg-a");
        service.syncUser(cfg, Map.of("uid", "u1", "dn", "cn=u1"), "tenant_a");
        service.syncUser(cfg, Map.of("uid", "u2", "dn", "cn=u2"), "tenant_a");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getPasswordHash())
                .isNotEqualTo(captor.getAllValues().get(1).getPasswordHash());
    }

    /** 定时同步必须遍历所有启用配置，而不是只查 system 租户。 */
    @Test
    void scheduledSync_coversAllTenants_notJustSystem() {
        LdapConfigEntity a = config("tenant_a", "cfg-a");
        LdapConfigEntity b = config("tenant_b", "cfg-b");
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(a, b));
        when(configRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());

        service.scheduledSync();

        verify(configRepository).findByEnabledTrue();
        // 两个租户的配置都被尝试同步（找不到具体配置即跳过，但不允许只处理 system）
        verify(configRepository, org.mockito.Mockito.times(2)).findByIdAndTenantId(any(), any());
    }

    @Test
    void createConfig_persistsAndReturns() {
        when(configRepository.save(any(LdapConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        LdapConfigEntity out = service.createConfig(
                "cfg", "ldap://x", "dc=x", "cn=admin", "pw",
                "(uid={0})", "(cn={0})", Map.of("uid", "username"),
                30, "tenant_a", UUID.randomUUID());

        assertThat(out.getName()).isEqualTo("cfg");
        assertThat(out.getTenantId()).isEqualTo("tenant_a");
        assertThat(out.getEnabled()).isFalse();
        verify(configRepository).save(any(LdapConfigEntity.class));
    }

    @Test
    void getSyncStatus_found_returns() {
        LdapConfigEntity cfg = config("t1", "cfg");
        when(configRepository.findByIdAndTenantId(cfg.getId(), "t1")).thenReturn(Optional.of(cfg));
        assertThat(service.getSyncStatus(cfg.getId(), "t1")).isSameAs(cfg);
    }

    @Test
    void getSyncStatus_missing_throws() {
        when(configRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSyncStatus(UUID.randomUUID(), "t1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void listConfigs_and_listMappings_delegate() {
        when(configRepository.findByTenantId("t1")).thenReturn(List.of(config("t1", "a")));
        when(mappingRepository.findByTenantId("t1")).thenReturn(List.of());
        assertThat(service.listConfigs("t1")).hasSize(1);
        assertThat(service.listMappings("t1")).isEmpty();
    }

    @Test
    void syncUser_existingLocalUser_linksMapping() {
        LdapConfigEntity cfg = config("t1", "cfg");
        when(mappingRepository.findByLdapUidAndTenantId("uid-9", "t1")).thenReturn(Optional.empty());
        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername("uid-9");
        when(userRepository.findByUsername("uid-9")).thenReturn(Optional.of(existing));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncUser(cfg, Map.of("uid", "uid-9", "dn", "cn=uid-9"), "t1");

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void syncUser_existingMapping_updatesStatus() {
        LdapConfigEntity cfg = config("t1", "cfg");
        LdapUserMappingEntity mapping = new LdapUserMappingEntity();
        mapping.setId(UUID.randomUUID());
        mapping.setTenantId("t1");
        mapping.setLdapUid("uid-5");
        when(mappingRepository.findByLdapUidAndTenantId("uid-5", "t1")).thenReturn(Optional.of(mapping));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncUser(cfg, Map.of("uid", "uid-5", "dn", "cn=uid-5"), "t1");

        assertThat(mapping.getSyncStatus()).isEqualTo("SYNCED");
        assertThat(mapping.getLastSyncedAt()).isNotNull();
    }

    @Test
    void syncUser_saveThrows_marksError() {
        LdapConfigEntity cfg = config("t1", "cfg");
        when(mappingRepository.findByLdapUidAndTenantId("uid-err", "t1")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("uid-err")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenThrow(new RuntimeException("db down"));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncUser(cfg, Map.of("uid", "uid-err", "dn", "cn=x"), "t1");

        ArgumentCaptor<LdapUserMappingEntity> captor = ArgumentCaptor.forClass(LdapUserMappingEntity.class);
        verify(mappingRepository).save(captor.capture());
        assertThat(captor.getValue().getSyncStatus()).isEqualTo("ERROR");
    }
}
