package com.nocobase.integration.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.integration.slack.SlackAppService;
import com.nocobase.realtime.RedisStompBridge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 入站消息落地链路集成测试 —— 真实跑 SQL，不 mock 被测的 {@link InboundMessageService}。
 *
 * <p>背景（本批两次打回的原因）：
 * <ul>
 *   <li>PHASE62 首轮：入站只 {@code log.info} 不落地；</li>
 *   <li>PHASE62 返工：补测试时把被测的 InboundMessageService 自身 mock 掉，
 *       导致落库 / 幂等 / 成员校验等核心逻辑零覆盖。</li>
 * </ul>
 * 本类因此直接 new 真实的 InboundMessageService（依赖中的 Stomp bridge 是外部
 * 通道、非被测主路径，用 mock 隔离），断言落在**数据库**里。
 */
@DataJpaTest
// 必须指定 test profile：该 profile 下 Flyway 关闭，迁移脚本是 PostgreSQL 语法
// （V1 就有 CREATE EXTENSION），在 H2 上跑会直接失败。
@ActiveProfiles("test")
class InboundMessageServiceIntegrationTest {

    /** PHASE96 T1: 映射不再回落到硬编码 UUID，改由 external_user_mapping 表驱动。 */
    private static final String TENANT = "tenant_default";

    @Autowired
    private ImMessageRepository messageRepository;
    @Autowired
    private ImChannelMemberRepository memberRepository;
    @Autowired
    private ExternalMessageLogRepository externalMessageLogRepository;
    @Autowired
    private ExternalUserMappingRepository externalUserMappingRepository;
    @Autowired
    private UserRepository userRepository;

    /**
     * 不使用 @Autowired：@DataJpaTest 切片不加载 Security 配置，没有 PasswordEncoder bean。
     * 这里手动构造一个，仅供自动建号时生成不可登录口令。
     */
    private final PasswordEncoder passwordEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    /** 外部通道，非被测主路径 —— 仅隔离，不影响落库断言。 */
    @MockBean
    private RedisStompBridge bridge;

    private InboundMessageService service;
    private UUID channelId;
    private UUID localUser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        service = new InboundMessageService(
                messageRepository, memberRepository, bridge, externalMessageLogRepository,
                externalUserMappingRepository, userRepository, passwordEncoder);
        ReflectionTestUtils.setField(service, "defaultChannelId", channelId.toString());
        ReflectionTestUtils.setField(service, "inboundEnabled", true);
        ReflectionTestUtils.setField(service, "autoProvisionUser", false);

        // 造一个本地用户 + 一条外部映射（替代原来的硬编码 MAPPED_USER）
        com.nocobase.auth.UserEntity u = new com.nocobase.auth.UserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername("inbound-it-" + u.getId());
        u.setPasswordHash("x");
        u.setTenantId(TENANT);
        u.setDisplayName("Inbound IT User");
        u.setCreatedAt(java.time.Instant.now());
        localUser = userRepository.save(u).getId();

        ExternalUserMappingEntity m = new ExternalUserMappingEntity();
        m.setId(UUID.randomUUID());
        m.setPlatform("slack");
        m.setExternalUserId("U-ext-1");
        m.setLocalUserId(localUser);
        m.setTenantId(TENANT);
        m.setCreatedAt(java.time.Instant.now());
        m.setUpdatedAt(java.time.Instant.now());
        externalUserMappingRepository.save(m);

        addMember(channelId, localUser);
    }

    private void addMember(UUID ch, UUID user) {
        ImChannelMemberEntity m = new ImChannelMemberEntity();
        m.setId(UUID.randomUUID());
        m.setChannelId(ch);
        m.setUserId(user);
        m.setTenantId(TENANT); // 非空列，缺失会触发 DataIntegrityViolationException
        memberRepository.save(m);
    }

    private ImMessageEntity inbound(String externalId, String text) {
        return service.processInboundMessage(
                TENANT, null, "slack", externalId, "U-ext-1", text, null);
    }

    /**
     * 手动 new 时 {@code @Value} 不会被注入（真实容器由 Spring 注入），
     * 因此需显式设置默认租户，否则落库时 tenant_id 为 null 触发非空约束。
     */
    private SlackAppService newSlackService() {
        SlackAppService slack = new SlackAppService(service);
        ReflectionTestUtils.setField(slack, "defaultTenantId", TENANT);
        return slack;
    }

    private long countByContent(String text) {
        return messageRepository.findAll().stream()
                .filter(m -> text.equals(m.getContent()))
                .count();
    }

    @Test
    void inbound_savesMessageToDatabase() {
        ImMessageEntity saved = inbound("Ev-1", "hello inbound");

        assertThat(saved).isNotNull();
        List<ImMessageEntity> all = messageRepository.findAll();
        assertThat(all).anyMatch(m -> "hello inbound".equals(m.getContent()));
        assertThat(saved.getChannelId()).isEqualTo(channelId);
        assertThat(saved.getSenderId()).isEqualTo(localUser);
    }

    @Test
    void inbound_duplicateExternalId_savedOnlyOnce() {
        inbound("Ev-dup", "dup msg");
        inbound("Ev-dup", "dup msg");

        assertThat(countByContent("dup msg")).isEqualTo(1);
    }

    @Test
    void inbound_differentExternalIds_savedSeparately() {
        inbound("Ev-a", "msg a");
        inbound("Ev-b", "msg b");

        assertThat(countByContent("msg a")).isEqualTo(1);
        assertThat(countByContent("msg b")).isEqualTo(1);
    }

    @Test
    void inbound_senderNotMember_dropped() {
        // 清掉成员关系，使映射用户不再是频道成员
        memberRepository.deleteAll();

        assertThat(inbound("Ev-nonmember", "should drop")).isNull();
        assertThat(countByContent("should drop")).isZero();
    }

    @Test
    void inbound_missingExternalId_dropped() {
        assertThat(inbound("", "no id")).isNull();
        assertThat(countByContent("no id")).isZero();
    }

    @Test
    void inbound_noDefaultChannel_dropped() {
        ReflectionTestUtils.setField(service, "defaultChannelId", "");

        assertThat(inbound("Ev-nochan", "no channel")).isNull();
        assertThat(countByContent("no channel")).isZero();
    }

    @Test
    void inbound_disabled_dropped() {
        ReflectionTestUtils.setField(service, "inboundEnabled", false);

        assertThat(inbound("Ev-disabled", "disabled")).isNull();
        assertThat(countByContent("disabled")).isZero();
    }

    /**
     * P0-A 回归防线：真实 Slack 事件是两层结构（顶层 type=event_callback），
     * 早期实现只取顶层 type 判 "message" → 真实消息永不落地（接口 200 但无数据）。
     * 用真实格式必须落库。
     */
    @Test
    void slack_realEventCallbackFormat_isSaved() throws Exception {
        SlackAppService slack = newSlackService();
        String payload = "{\"type\":\"event_callback\",\"event_id\":\"Ev-real-1\","
                + "\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U-ext-1\","
                + "\"text\":\"real slack format\",\"ts\":\"1710000000.000100\"}}";

        slack.handleEvent(mapper.readTree(payload));

        assertThat(countByContent("real slack format")).isEqualTo(1);
    }

    /** 兼容扁平格式（无 event 包裹）。 */
    @Test
    void slack_flatFormat_isSaved() throws Exception {
        SlackAppService slack = newSlackService();
        String payload = "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U-ext-1\","
                + "\"text\":\"flat format\",\"ts\":\"1710000000.000200\"}";

        slack.handleEvent(mapper.readTree(payload));

        assertThat(countByContent("flat format")).isEqualTo(1);
    }

    /** 机器人消息（bot_message）不应落库，避免回声风暴。 */
    @Test
    void slack_botMessage_isIgnored() throws Exception {
        SlackAppService slack = newSlackService();
        String payload = "{\"type\":\"event_callback\",\"event_id\":\"Ev-bot\","
                + "\"event\":{\"type\":\"message\",\"subtype\":\"bot_message\","
                + "\"channel\":\"C123\",\"user\":\"U123\",\"text\":\"bot echo\"}}";

        slack.handleEvent(mapper.readTree(payload));

        assertThat(countByContent("bot echo")).isZero();
    }

    // ============================================================
    //  PHASE96 T1: 映射真实现 —— 不再回落到硬编码 UUID
    // ============================================================

    /**
     * 未配置映射且未开自动建号时必须丢弃，而不是回落到固定 UUID。
     *
     * <p>这是假实现的证伪点：修复前任何外部用户都会"映射成功"到 00000000-…-0001。
     */
    @Test
    void inbound_unknownExternalUser_dropped() {
        // "U-unknown" 没有映射记录，且 autoProvisionUser=false
        assertThat(service.processInboundMessage(
                TENANT, null, "slack", "Ev-unknown", "U-unknown", "no mapping", null)).isNull();
        assertThat(countByContent("no mapping")).isZero();
    }

    /**
     * 两个不同外部用户 → 两个不同本地用户（auto-provision 开启时）。
     *
     * <p>注意：新 provision 的用户尚未加入频道，按设计会被成员校验丢弃（返回 null）——
     * 那是正确的"显式失败"。这里断言的是**映射表的差异化**：两个不同外部用户
     * 必须解析到两个不同 local_user_id，而不是都回落到同一个硬编码 UUID。
     */
    @Test
    void inbound_autoProvision_distinctLocalUsers() {
        ReflectionTestUtils.setField(service, "autoProvisionUser", true);

        service.processInboundMessage(
                TENANT, channelId, "slack", "Ev-p1", "U-provision-a", "pa", null);
        service.processInboundMessage(
                TENANT, channelId, "slack", "Ev-p2", "U-provision-b", "pb", null);

        List<UUID> localIds = externalUserMappingRepository.findAll().stream()
                .filter(m -> "slack".equals(m.getPlatform()))
                .filter(m -> m.getExternalUserId().startsWith("U-provision-"))
                .map(ExternalUserMappingEntity::getLocalUserId)
                .distinct()
                .toList();
        assertThat(localIds).hasSize(2);
        // 关键：不复用硬编码 UUID 00000000-…-0001
        assertThat(localIds).doesNotContain(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    /** 映射存在但 local_user_id 指向不存在/跨租户的用户 → 必须丢弃，不得静默成功。 */
    @Test
    void inbound_staleMapping_dropped() {
        ExternalUserMappingEntity stale = new ExternalUserMappingEntity();
        stale.setId(UUID.randomUUID());
        stale.setPlatform("slack");
        stale.setExternalUserId("U-stale");
        stale.setLocalUserId(UUID.randomUUID());  // 指向不存在的用户
        stale.setTenantId(TENANT);
        stale.setCreatedAt(java.time.Instant.now());
        stale.setUpdatedAt(java.time.Instant.now());
        externalUserMappingRepository.save(stale);

        assertThat(service.processInboundMessage(
                TENANT, null, "slack", "Ev-stale", "U-stale", "stale", null)).isNull();
        assertThat(countByContent("stale")).isZero();
    }
}
