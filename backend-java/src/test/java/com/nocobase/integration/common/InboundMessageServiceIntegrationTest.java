package com.nocobase.integration.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.im.ImChannelMemberRepository;
import com.nocobase.im.ImMessageRepository;
import com.nocobase.im.entity.ImChannelMemberEntity;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.integration.slack.SlackAppService;
import com.nocobase.realtime.RedisStompBridge;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    /** mapExternalUser 的回落用户（实现固定映射为此 ID）。 */
    private static final UUID MAPPED_USER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TENANT = "tenant_default";

    @Autowired
    private ImMessageRepository messageRepository;
    @Autowired
    private ImChannelMemberRepository memberRepository;
    @Autowired
    private ExternalMessageLogRepository externalMessageLogRepository;

    /** 外部通道，非被测主路径 —— 仅隔离，不影响落库断言。 */
    @MockBean
    private RedisStompBridge bridge;

    private InboundMessageService service;
    private UUID channelId;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        service = new InboundMessageService(
                messageRepository, memberRepository, bridge, externalMessageLogRepository);
        ReflectionTestUtils.setField(service, "defaultChannelId", channelId.toString());
        ReflectionTestUtils.setField(service, "inboundEnabled", true);
        addMember(channelId, MAPPED_USER);
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
        assertThat(saved.getSenderId()).isEqualTo(MAPPED_USER);
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
        // 清掉成员关系，使 MAPPED_USER 不再是频道成员
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
                + "\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U123\","
                + "\"text\":\"real slack format\",\"ts\":\"1710000000.000100\"}}";

        slack.handleEvent(mapper.readTree(payload));

        assertThat(countByContent("real slack format")).isEqualTo(1);
    }

    /** 兼容扁平格式（无 event 包裹）。 */
    @Test
    void slack_flatFormat_isSaved() throws Exception {
        SlackAppService slack = newSlackService();
        String payload = "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U123\","
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
}
