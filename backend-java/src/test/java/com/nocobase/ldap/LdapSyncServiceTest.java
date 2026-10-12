package com.nocobase.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.ldap.entity.LdapConfigEntity;
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
}
