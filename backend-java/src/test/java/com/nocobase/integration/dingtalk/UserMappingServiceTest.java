package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE105: UserMappingService 测试。 */
class UserMappingServiceTest {

    private UserRepository userRepository;
    private UserMappingService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserMappingService(userRepository);
    }

    @Test
    void mapUser_firstTime_createsMapping() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserEntity result = service.mapUser("union123", userId.toString(), "t1");

        assertThat(result).isSameAs(user);
    }

    @Test
    void mapUser_cachedMapping_returnsUser() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 第一次建立映射
        service.mapUser("union123", userId.toString(), "t1");
        // 第二次应该从缓存返回
        UserEntity result = service.mapUser("union123", userId.toString(), "t1");

        assertThat(result).isSameAs(user);
    }

    @Test
    void mapUser_invalidUUID_throwsException() {
        assertThatThrownBy(() -> service.mapUser("union123", "not-a-uuid", "t1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapUser_userNotFound_throwsException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.mapUser("union123", userId.toString(), "t1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射用户不存在");
    }

    @Test
    void findByDingTalkUserId_existing_returnsUser() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.mapUser("union123", userId.toString(), "t1");

        Optional<UserEntity> result = service.findByDingTalkUserId("union123");

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(user);
    }

    @Test
    void findByDingTalkUserId_unknown_returnsEmpty() {
        Optional<UserEntity> result = service.findByDingTalkUserId("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void getAllMappings_returnsCopy() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.mapUser("union123", userId.toString(), "t1");

        var all = service.getAllMappings();
        assertThat(all).hasSize(1);
        assertThat(all).containsKey("union123");

        // 修改副本不应影响原始缓存
        all.put("extra", userId);
        var all2 = service.getAllMappings();
        assertThat(all2).hasSize(1); // 不应被修改
    }

    @Test
    void getMappings_returnsListWithTenantInfo() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.mapUser("union123", userId.toString(), "t1");

        var list = service.getMappings("t1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("dingtalkUserId")).isEqualTo("union123");
        assertThat(list.get(0).get("username")).isEqualTo("alice");
        assertThat(list.get(0).get("tenantId")).isEqualTo("t1");
    }

    @Test
    void getMappings_empty_returnsEmptyList() {
        var list = service.getMappings("t1");
        assertThat(list).isEmpty();
    }
}