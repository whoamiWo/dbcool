package com.nocobase.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocobase.integration.common.InboundMessageService;
import com.nocobase.integration.credential.TenantCredentialService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE105: SlackAppService 成功路径测试（反射注入 RestTemplate + @Value 字段）。
 *
 * <p>覆盖 handleOAuthCallback 的完整链路（token 换取 → 工作区落内存 → 返回），
 * 以及 getSigningSecret/getBotToken/getTeamId 的非空取值分支。
 */
class SlackOAuthSuccessTest {

    private SlackAppService service;
    private RestTemplate rest;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new SlackAppService(mock(InboundMessageService.class), mock(TenantCredentialService.class));
        rest = mock(RestTemplate.class);
        // private final RestTemplate → 反射替换
        ReflectionTestUtils.setField(service, "restTemplate", rest);
        // @Value 凭证
        ReflectionTestUtils.setField(service, "clientId", "cid");
        ReflectionTestUtils.setField(service, "clientSecret", "csec");
        ReflectionTestUtils.setField(service, "redirectUri", "https://app/cb");
        ReflectionTestUtils.setField(service, "signingSecret", "sign");
        ReflectionTestUtils.setField(service, "botToken", "xoxb-1");
        ReflectionTestUtils.setField(service, "teamId", "T1");
    }

    private void stubOAuth(JsonNode resp) {
        when(rest.postForObject(anyString(), any(), eq(JsonNode.class))).thenReturn(resp);
    }

    private ObjectNode okResp() {
        ObjectNode root = om.createObjectNode();
        root.put("ok", true);
        root.put("access_token", "xoxb-new");
        ObjectNode team = om.createObjectNode();
        team.put("id", "T-NEW");
        team.put("name", "新工作区");
        root.set("team", team);
        root.put("bot_user_id", "U-BOT");
        return root;
    }

    @Test
    void handleOAuthCallback_success_returnsTeamInfo() throws Exception {
        stubOAuth(okResp());

        Map<String, Object> out = service.handleOAuthCallback("code-1");

        assertThat(out.get("code")).isEqualTo(0);
        assertThat(out.get("message")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) out.get("data");
        assertThat(data.get("teamId")).isEqualTo("T-NEW");
        assertThat(data.get("teamName")).isEqualTo("新工作区");
        assertThat(data.get("botUserId")).isEqualTo("U-BOT");
    }

    @Test
    void handleOAuthCallback_apiNotOk_throws() {
        ObjectNode resp = om.createObjectNode();
        resp.put("ok", false);
        resp.put("error", "invalid_code");
        stubOAuth(resp);

        assertThatThrownBy(() -> service.handleOAuthCallback("bad"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid_code");
    }

    @Test
    void handleOAuthCallback_nullResponse_throws() {
        when(rest.postForObject(anyString(), any(), eq(JsonNode.class))).thenReturn(null);

        assertThatThrownBy(() -> service.handleOAuthCallback("code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("响应为空");
    }

    @Test
    void handleOAuthCallback_missingAccessToken_throws() {
        ObjectNode resp = om.createObjectNode();
        resp.put("ok", true); // 缺 access_token
        stubOAuth(resp);

        assertThatThrownBy(() -> service.handleOAuthCallback("code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("access_token");
    }

    @Test
    void isConfigured_allCredentialsPresent_true() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void getters_returnConfiguredValues() {
        assertThat(service.getSigningSecret()).isEqualTo("sign");
        assertThat(service.getBotToken()).isEqualTo("xoxb-1");
        assertThat(service.getTeamId()).isEqualTo("T1");
    }

    @Test
    void getOAuthUrl_containsClientIdAndRedirect() {
        String url = service.getOAuthUrl();
        assertThat(url).contains("client_id=cid").contains("redirect_uri=https://app/cb");
    }
}
