package com.nocobase.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Slash 命令端点契约测试（断链修复）。
 *
 * <p>前端 {@code GET /api/im/slash/commands} 此前无后端端点；
 * 此处断言端点返回真实注册表内容（而非硬编码列表），防止与注册表漂移。
 */
class ImSlashControllerTest {

    private final ImSlashController controller =
            new ImSlashController(SlashCommandRegistry.defaultRegistry());

    @Test
    void commands_returnsRegistryContents() {
        Map<String, Object> resp = controller.commands();

        assertThat(resp.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> commands = (List<Map<String, String>>) data.get("commands");

        // 内置 5 个命令且均带描述（描述为空说明注册表与端点不同步）
        assertThat(commands).hasSize(5);
        assertThat(commands).allSatisfy(c -> {
            assertThat(c.get("name")).startsWith("/");
            assertThat(c.get("description")).isNotEmpty();
        });
    }

    @Test
    void commands_reflectsCustomRegistration() {
        SlashCommandRegistry reg = new SlashCommandRegistry();
        reg.register("standup", "发起站会", (content, ctx) -> { });
        var c = new ImSlashController(reg);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) c.commands().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> commands = (List<Map<String, String>>) data.get("commands");

        // 端点应反映注册表，而非固定 5 个 —— 证明没有硬编码
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).get("name")).isEqualTo("/standup");
    }
}
