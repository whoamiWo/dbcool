package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A-2 真实 DB 验证：三条外部建号路径（钉钉 / OIDC / LDAP）落库成功且无本地密码。
 *
 * <p>修复前 {@code password_hash} 是 NOT NULL，且三处都不写密码 → 每条路径都会
 * 抛约束冲突；旧单测因 mock 掉 save 而永远看不到这个失败。
 */
@DataJpaTest
@ActiveProfiles("test")
class UserProvisioningRealDbTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("钉钉/OIDC/LDAP 三个账号均可落库，password_set=false 且 hash 为空")
    void threeExternalProviders_persistWithoutLocalPassword() {
        UserProvisioningService service = new UserProvisioningService(userRepository);

        UserEntity ding = service.createExternal("t1", "dt_u_" + UUID.randomUUID(),
                "钉钉用户", null, "DINGTALK");
        UserEntity oidc = service.createExternal("t1", "oidc_u_" + UUID.randomUUID(),
                "OIDC 用户", "o@corp.com", "OIDC");
        UserEntity ldap = service.createExternal("t1", "ldap_u_" + UUID.randomUUID(),
                "LDAP 用户", null, "LDAP");

        for (UserEntity u : new UserEntity[]{ding, oidc, ldap}) {
            UserEntity reloaded = userRepository.findById(u.getId()).orElseThrow();
            assertThat(reloaded.getPasswordHash()).isNull();
            assertThat(reloaded.isPasswordSet()).isFalse();
            assertThat(reloaded.getTenantId()).isEqualTo("t1");
            assertThat(reloaded.isEnabled()).isTrue();
        }
        assertThat(userRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("password_hash 已放开 NOT NULL：可真实插入空密码账号")
    void passwordHashColumn_isNullable() {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("legacy_" + UUID.randomUUID());
        u.setTenantId("t1");
        u.setPasswordHash(null);
        u.setPasswordSet(false);
        u.setEnabled(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setCreatedAt(java.time.Instant.now());

        UserEntity saved = userRepository.saveAndFlush(u);
        assertThat(saved.getId()).isNotNull();
    }
}
