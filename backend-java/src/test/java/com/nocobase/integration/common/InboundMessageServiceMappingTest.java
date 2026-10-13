package com.nocobase.integration.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.realtime.RedisStompBridge;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PHASE96 T1: 外部用户映射逻辑单元测试（不依赖 JPA 建表）。
 *
 * <p>对应需求：{@code InboundMessageService.mapExternalUser()} 不得再回落到硬编码 UUID
 * {@code 00000000-0000-0000-0000-000000000001}，必须由 {@code external_user_mapping} 表驱动。
 *
 * <p>为什么不用 {@code @DataJpaTest}：项目里 32 个实体的 JSON 字段在 Hibernate 6 +
 * {@code H2Dialect} 下会生成 H2 不认识的 {@code jsonb} 类型名，导致建表失败 —— 这是存量问题
 * （{@code InboundMessageServiceIntegrationTest} 基线即 3 failures），与本批无关，
 * 不在本批扩大改动面。映射逻辑本身用 Mockito 覆盖，落库链路仍由既有集成测试覆盖。
 */
class InboundMessageServiceMappingTest {

    private static final String TENANT = "tenant_default";
    private static final UUID HARDCODED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ImMessageRepository messageRepository;
    private ImChannelMemberRepository memberRepository;
    private RedisStompBridge bridge;
    private ExternalMessageLogRepository externalMessageLogRepository;
    private ExternalUserMappingRepository mappingRepository;
    private UserRepository userRepository;

    private InboundMessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ImMessageRepository.class);
        memberRepository = mock(ImChannelMemberRepository.class);
        bridge = mock(RedisStompBridge.class);
        externalMessageLogRepository = mock(ExternalMessageLogRepository.class);
        mappingRepository = mock(ExternalUserMappingRepository.class);
        userRepository = mock(UserRepository.class);

        service = new InboundMessageService(
                messageRepository, memberRepository, bridge, externalMessageLogRepository,
                mappingRepository, userRepository, new BCryptPasswordEncoder(),
                // PHASE99 T3: 入站消息审计依赖，mock 隔离
                mock(com.nocobase.audit.AuditService.class));
        ReflectionTestUtils.setField(service, "inboundEnabled", true);
        ReflectionTestUtils.setField(service, "autoProvisionUser", false);
    }

    private ImMessageEntity message(UUID senderId) {
        ImMessageEntity m = new ImMessageEntity();
        m.setId(UUID.randomUUID());
        m.setTenantId(TENANT);
        m.setChannelId(UUID.randomUUID());
        m.setSenderId(senderId);
        m.setContent("x");
        m.setContentType("text");
        return m;
    }

    private void stubSavedMessage(UUID senderId) {
        when(messageRepository.save(any(ImMessageEntity.class)))
                .thenAnswer(inv -> {
                    ImMessageEntity m = inv.getArgument(0);
                    m.setId(UUID.randomUUID());
                    return m;
                });
        when(memberRepository.existsByChannelIdAndUserId(any(), eq(senderId))).thenReturn(true);
    }

    /** 命中映射 → 用映射指向的本地用户落库。 */
    @Test
    void mappedUser_isUsed() {
        UUID local = UUID.randomUUID();
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId("slack", "U-1", TENANT))
                .thenReturn(Optional.of(mapping(local)));
        when(userRepository.findByIdAndTenantId(local, TENANT))
                .thenReturn(Optional.of(user(local)));
        stubSavedMessage(local);

        ImMessageEntity saved = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-1", "U-1", "hi", null);

        assertThat(saved).isNotNull();
        assertThat(saved.getSenderId()).isEqualTo(local);
    }

    /** 未命中映射且未开自动建号 → 丢弃，绝不回落到硬编码 UUID。 */
    @Test
    void unmappedUser_isDropped_notFallbackToHardcoded() {
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId("slack", "U-unknown", TENANT))
                .thenReturn(Optional.empty());

        ImMessageEntity saved = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-2", "U-unknown", "hi", null);

        assertThat(saved).isNull();
        verify(messageRepository, never()).save(any());
    }

    /** 映射指向的用户不存在/跨租户 → 丢弃，不静默成功。 */
    @Test
    void staleMapping_isDropped() {
        UUID ghost = UUID.randomUUID();
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId("slack", "U-stale", TENANT))
                .thenReturn(Optional.of(mapping(ghost)));
        when(userRepository.findByIdAndTenantId(ghost, TENANT)).thenReturn(Optional.empty());

        ImMessageEntity saved = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-3", "U-stale", "hi", null);

        assertThat(saved).isNull();
        verify(messageRepository, never()).save(any());
    }

    /**
     * PHASE96 B0 回归：不同租户下同一外部用户不得串到对方的本地账号。
     *
     * <p>username = {@code source + ":" + externalUserId}，不含租户；而
     * {@code findByUsername} 是全局查询。若不过滤 tenantId，本租户入站会命中
     * 他租户已建的同名用户，并回写出一条 "tenantId=本租户 但 localUserId 属于他租户"
     * 的错配映射（跨租户串号）。该路径在 auto-provision-user 默认 false 时不会触发，
     * 默认翻转为 true 后成为默认路径，因此必须有回归锁住。
     */
    @Test
    void autoProvision_sameExternalUserDifferentTenant_noCrossTenantReuse() {
        ReflectionTestUtils.setField(service, "autoProvisionUser", true);
        UserEntity otherTenantUser = new UserEntity();
        otherTenantUser.setId(UUID.randomUUID());
        otherTenantUser.setUsername("slack:shared-1");
        otherTenantUser.setTenantId("tenant_other");

        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(anyString(), anyString(), eq(TENANT)))
                .thenReturn(Optional.empty());
        // 全局能按 username 查到，但那个用户属于另一个租户 —— 不得复用
        when(userRepository.findByUsername("slack:shared-1"))
                .thenReturn(Optional.of(otherTenantUser));
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ImMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(true);
        when(mappingRepository.save(any(ExternalUserMappingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ImMessageEntity saved = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-x1", "shared-1", "hi", null);

        assertThat(saved).isNotNull();
        assertThat(saved.getSenderId()).isNotEqualTo(otherTenantUser.getId());

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    /** 自动建号开启：两个不同外部用户 → 两个不同本地用户。 */
    @Test
    void autoProvision_createsDistinctLocalUsers() {
        ReflectionTestUtils.setField(service, "autoProvisionUser", true);
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(anyString(), anyString(), eq(TENANT)))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ImMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(true);
        when(mappingRepository.save(any(ExternalUserMappingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ImMessageEntity a = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-p1", "U-a", "pa", null);
        ImMessageEntity b = service.processInboundMessage(
                TENANT, UUID.randomUUID(), "slack", "Ev-p2", "U-b", "pb", null);

        assertThat(a.getSenderId()).isNotEqualTo(b.getSenderId());
        // 关键：不复用硬编码 UUID
        assertThat(a.getSenderId()).isNotEqualTo(HARDCODED);
        assertThat(b.getSenderId()).isNotEqualTo(HARDCODED);
        verify(mappingRepository, org.mockito.Mockito.times(2)).save(any(ExternalUserMappingEntity.class));
    }

    /** 自动建号时口令必须是不可预测的随机值（不是可本地登录的占位口令）。 */
    @Test
    void autoProvision_passwordIsRandom_notPredictable() {
        ReflectionTestUtils.setField(service, "autoProvisionUser", true);
        when(mappingRepository.findByPlatformAndExternalUserIdAndTenantId(anyString(), anyString(), eq(TENANT)))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processInboundMessage(TENANT, UUID.randomUUID(), "slack", "Ev-pw", "U-pw", "x", null);

        var captor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash())
                .isNotEqualTo("LDAP_SYNCED")
                .isNotBlank()
                .startsWith("$2"); // bcrypt
    }

    private ExternalUserMappingEntity mapping(UUID localUserId) {
        ExternalUserMappingEntity m = new ExternalUserMappingEntity();
        m.setId(UUID.randomUUID());
        m.setPlatform("slack");
        m.setExternalUserId("U-1");
        m.setLocalUserId(localUserId);
        m.setTenantId(TENANT);
        return m;
    }

    private UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername("mapped-" + id);
        u.setTenantId(TENANT);
        return u;
    }
}
