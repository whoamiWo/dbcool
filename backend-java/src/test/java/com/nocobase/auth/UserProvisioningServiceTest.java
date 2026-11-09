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
        existing.setTenantId("t1");
        when(userRepository.findByTenantIdAndUsername("t1", "dt_u1"))
                .thenReturn(Optional.of(existing));

        UserEntity result = service.provisionExternalAccount("t1", "dt_u1", "x", null, "DINGTALK");

        assertThat(result).isSameAs(existing);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("F2：跨租户同名外部账号 → 追加租户后缀，不改写其他租户用户")
    void createExternal_crossTenantNameCollision_usesStableSuffix() {
        UserEntity tenantA = new UserEntity();
        tenantA.setId(UUID.randomUUID());
        tenantA.setUsername("fs_ou_1");
        tenantA.setTenantId("tenantA");
        tenantA.setDisplayName("A 公司员工");
        when(userRepository.findByUsername("fs_ou_1")).thenReturn(Optional.of(tenantA));

        UserEntity created = service.createExternal(
                "tenantB", "fs_ou_1", "B 公司员工", null, "FEISHU", "ou_1");

        assertThat(created.getTenantId()).isEqualTo("tenantB");
        assertThat(created.getUsername()).startsWith("fs_ou_1_");
        assertThat(created.getExternalSource()).isEqualTo("FEISHU");
        assertThat(created.getExternalId()).isEqualTo("ou_1");
        // A 租户账号未被改写
        assertThat(tenantA.getDisplayName()).isEqualTo("A 公司员工");
        verify(userRepository, org.mockito.Mockito.never()).save(tenantA);
    }

    @Test
    @DisplayName("createExternal：username/tenantId 为空直接拒绝")
    void createExternal_blankArgsRejected() {
        assertThatThrownBy(() -> service.createExternal("t1", " ", "x", null, "FEISHU", "ou"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createExternal(null, "fs_x", "x", null, "FEISHU", "ou"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("createExternal：同租户已存在 → 明确报错，不静默复用")
    void createExternal_sameTenantCollision_rejected() {
        UserEntity same = new UserEntity();
        same.setId(UUID.randomUUID());
        same.setUsername("fs_ou");
        same.setTenantId("t1");
        when(userRepository.findByUsername("fs_ou")).thenReturn(Optional.of(same));

        assertThatThrownBy(() -> service.createExternal("t1", "fs_ou", "x", null, "FEISHU", "ou"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("F2：租户后缀账号已存在 → 幂等复用并补齐 external_id")
    void createExternal_reusesSuffixedTenantAccount() {
        UserEntity tenantA = new UserEntity();
        tenantA.setId(UUID.randomUUID());
        tenantA.setUsername("fs_ou");
        tenantA.setTenantId("tenantA");
        UserEntity tenantB = new UserEntity();
        tenantB.setId(UUID.randomUUID());
        tenantB.setUsername("fs_ou_deadbeef");
        tenantB.setTenantId("tenantB");

        when(userRepository.findByUsername("fs_ou")).thenReturn(Optional.of(tenantA));
        when(userRepository.findByUsername(org.mockito.ArgumentMatchers.startsWith("fs_ou_")))
                .thenReturn(Optional.of(tenantB));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = service.createExternal(
                "tenantB", "fs_ou", "B", null, "FEISHU", "ou");

        assertThat(result).isSameAs(tenantB);
        assertThat(tenantB.getExternalSource()).isEqualTo("FEISHU");
        assertThat(tenantB.getExternalId()).isEqualTo("ou");
    }

    @Test
    @DisplayName("F2：后缀名仍被其他租户占用 → 再追加随机后缀")
    void createExternal_suffixedNameAlsoTaken_appendsRandomSuffix() {
        UserEntity tenantA = new UserEntity();
        tenantA.setId(UUID.randomUUID());
        tenantA.setUsername("fs_ou");
        tenantA.setTenantId("tenantA");
        UserEntity tenantC = new UserEntity();
        tenantC.setId(UUID.randomUUID());
        tenantC.setUsername("fs_ou_deadbeef");
        tenantC.setTenantId("tenantC");

        when(userRepository.findByUsername("fs_ou")).thenReturn(Optional.of(tenantA));
        when(userRepository.findByUsername(org.mockito.ArgumentMatchers.startsWith("fs_ou_")))
                .thenReturn(Optional.of(tenantC));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity created = service.createExternal(
                "tenantB", "fs_ou", "B", null, "FEISHU", "ou");

        assertThat(created.getTenantId()).isEqualTo("tenantB");
        assertThat(created.getUsername()).startsWith("fs_ou_");
        assertThat(created.getUsername()).isNotEqualTo("fs_ou_deadbeef");
    }

    @Test
    @DisplayName("createExternal：数据库唯一键冲突 → 明确错误而非吞掉")
    void createExternal_uniqueViolation_isWrapped() {
        when(userRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.createExternal(
                "t1", "fs_ou", "x", null, "FEISHU", "ou"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("建号冲突");
    }

    @Test
    @DisplayName("F2：createExternal 写入显式外部身份，禁用原因为空")
    void createExternal_writesExternalIdentity() {
        UserEntity saved = service.createExternal(
                "t1", "fs_ou_2", "张三", null, "FEISHU", "ou_2");

        assertThat(saved.getExternalSource()).isEqualTo("FEISHU");
        assertThat(saved.getExternalId()).isEqualTo("ou_2");
        assertThat(saved.getDisableReason()).isNull();
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
