package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A-2：统一建号路径（外部托管账号无本地密码 + 幂等）。 */
class UserProvisioningServiceTest {

    private UserRepository userRepository;
    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserProvisioningService(userRepository);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("新建外部账号：passwordHash=null 且 passwordSet=false")
    void createExternal_hasNoLocalPassword() {
        UserEntity saved = service.createExternal("t1", "dt_u1", "张三", "z@corp.com", "DINGTALK");

        assertThat(saved.getPasswordHash()).isNull();
        assertThat(saved.isPasswordSet()).isFalse();
        assertThat(saved.getTenantId()).isEqualTo("t1");
        assertThat(saved.getDisplayName()).isEqualTo("张三");
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("displayName 为空 → 退化为 username；source 只影响日志")
    void createExternal_blankNameFallsBackToUsername() {
        UserEntity saved = service.createExternal("t1", "ldap_u1", " ", null, "LDAP");
        assertThat(saved.getDisplayName()).isEqualTo("ldap_u1");
    }

    @Test
    @DisplayName("provisionExternalAccount 幂等：已存在直接返回，不新建")
    void provisionExternalAccount_existing_returned() {
        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        existing.setUsername("dt_u1");
        when(userRepository.findByUsername("dt_u1")).thenReturn(Optional.of(existing));

        UserEntity result = service.provisionExternalAccount("t1", "dt_u1", "x", null, "DINGTALK");

        assertThat(result).isSameAs(existing);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("参数校验：空 username / tenantId 直接拒绝")
    void provisionExternalAccount_blankArgsRejected() {
        assertThatThrownBy(() -> service.provisionExternalAccount("t1", " ", null, null, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.provisionExternalAccount(null, "u", null, null, "X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markExternallyManaged 清空本地密码（历史 bcrypt 占位串纠偏）")
    void markExternallyManaged_clearsPassword() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("u1");
        user.setPasswordHash("$2a$10$whatever");
        user.setPasswordSet(true);

        service.markExternallyManaged(user);

        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.isPasswordSet()).isFalse();
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("u1");
    }
}
