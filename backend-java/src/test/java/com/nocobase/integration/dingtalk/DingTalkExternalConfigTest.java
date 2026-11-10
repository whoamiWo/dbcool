package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DingTalkExternalConfigTest {
    private final DingTalkExternalConfig parser = new DingTalkExternalConfig(new ObjectMapper());

    @Test
    void mapsOnlyExplicitScalarFieldsAndExternalOriginator() {
        var result = parser.resolve("PROC-1", """
                {"corpId":"corp-a","approversMode":"OA_TEMPLATE","deptId":-1,
                 "formComponentValues":[{"name":"请假天数","field":"days","required":true}]}
                """, Map.of("days", 3, "ignored", "secret"), "ding-user-1");
        assertThat(result.corpId()).isEqualTo("corp-a");
        assertThat(result.originatorUserId()).isEqualTo("ding-user-1");
        assertThat(result.formComponentValues()).containsExactly(Map.of("name", "请假天数", "value", "3"));
    }

    @Test
    void missingOriginatorOrMapping_failsBeforeHttp() {
        assertThatThrownBy(() -> parser.resolve("PROC", """
                {"corpId":"corp","approversMode":"OA_TEMPLATE","deptId":-1,
                 "formComponentValues":[{"name":"天数","field":"days"}]}
                """, Map.of("days", 1), null)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("钉钉 userid");
        assertThatThrownBy(() -> parser.resolve("PROC", """
                {"corpId":"corp","approversMode":"OA_TEMPLATE","deptId":-1,
                 "formComponentValues":[{"name":"详情","field":"detail"}]}
                """, Map.of("detail", Map.of("unsafe", true)), "user")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("标量");
    }

    @Test
    void oaTemplateRequiresExplicitDeptId() {
        assertThatThrownBy(() -> parser.resolve("PROC", """
                {"corpId":"corp","approversMode":"OA_TEMPLATE",
                 "formComponentValues":[{"name":"天数","field":"days"}]}
                """, Map.of("days", 1), "user")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deptId");
    }
}
