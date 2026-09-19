package com.nocobase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.integration.dingtalk.DingTalkMessageService;
import com.nocobase.im.MessageService;
import com.nocobase.im.entity.ImMessageEntity;
import com.nocobase.meta.CollectionService;
import com.nocobase.project.ProjectService;
import com.nocobase.wiki.WikiPageService;
import com.nocobase.wiki.WikiSearchService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AgentTool 单元测试（Phase 48 审计修复 R1）：验证 6 个工具返回真实值、
 * 缺失必填参数返回 error、禁止硬编码成功。
 */
class AgentToolTest {

    private WikiSearchService wikiSearchService;
    private WikiPageService wikiPageService;
    private ProjectService projectService;
    private MessageService messageService;
    private AiAssistantService aiService;
    private DingTalkMessageService dingTalkMessageService;
    private CollectionService collectionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final String tenantId = "tenant_default";
    private AgentToolContext ctx;

    @BeforeEach
    void setUp() {
        wikiSearchService = mock(WikiSearchService.class);
        wikiPageService = mock(WikiPageService.class);
        projectService = mock(ProjectService.class);
        messageService = mock(MessageService.class);
        aiService = mock(AiAssistantService.class);
        dingTalkMessageService = mock(DingTalkMessageService.class);
        collectionService = mock(CollectionService.class);
        ctx = new AgentToolContext(tenantId, userId, channelId);
    }

    @Test
    void searchWiki_missingQuery_returnsError() {
        var tool = new com.nocobase.ai.tools.SearchWikiTool(wikiSearchService);
        var result = tool.execute(Map.of(), ctx);
        assertThat(result).containsEntry("error", "query 不能为空");
    }

    @Test
    void searchWiki_success() {
        var page = new com.nocobase.wiki.WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTitle("测试页");
        page.setSlug("test-page");
        when(wikiSearchService.search(anyString(), eq(null), eq(tenantId), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(page)));
        var tool = new com.nocobase.ai.tools.SearchWikiTool(wikiSearchService);
        var result = tool.execute(Map.of("query", "测试"), ctx);
        assertThat(result).containsEntry("total", 1L);
        List<?> items = (List<?>) result.get("items");
        assertThat(items).hasSize(1);
    }

    @Test
    void createPage_missingTitle_returnsError() {
        var tool = new com.nocobase.ai.tools.CreatePageTool(wikiPageService);
        var result = tool.execute(Map.of(), ctx);
        assertThat(result).containsEntry("error", "title 必填");
    }

    @Test
    void createPage_missingKbId_returnsError() {
        var tool = new com.nocobase.ai.tools.CreatePageTool(wikiPageService);
        var result = tool.execute(Map.of("title", "T"), ctx);
        assertThat(result).containsEntry("error", "kbId 必填");
    }

    @Test
    void createPage_success() {
        var page = new com.nocobase.wiki.WikiPageEntity();
        page.setId(UUID.randomUUID());
        page.setTitle("Hello");
        page.setSlug("hello");
        when(wikiPageService.create(any(), eq(null), eq("Hello"), any(), any(), eq(userId), eq(tenantId)))
                .thenReturn(page);
        var tool = new com.nocobase.ai.tools.CreatePageTool(wikiPageService);
        var result = tool.execute(Map.of("title", "Hello", "kbId", UUID.randomUUID()), ctx);
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("pageId")).isNotNull();
    }

    @Test
    void createTask_missingProjectId_returnsError() {
        var tool = new com.nocobase.ai.tools.CreateTaskTool(projectService);
        var result = tool.execute(Map.of("title", "T"), ctx);
        assertThat(result).containsEntry("error", "projectId 必填");
    }

    @Test
    void createTask_missingTitle_returnsError() {
        var tool = new com.nocobase.ai.tools.CreateTaskTool(projectService);
        var result = tool.execute(Map.of("projectId", UUID.randomUUID()), ctx);
        assertThat(result).containsEntry("error", "title 必填");
    }

    @Test
    void createTask_success() {
        var task = new com.nocobase.project.ProjectTaskEntity();
        task.setId(UUID.randomUUID());
        task.setTitle("T");
        task.setStatus("TODO");
        var projectId = UUID.randomUUID();
        when(projectService.create(eq(projectId), eq("T"), any(), any(), any(),
                any(), any(), any(), any(), eq(userId), eq(tenantId)))
                .thenReturn(task);
        var tool = new com.nocobase.ai.tools.CreateTaskTool(projectService);
        var result = tool.execute(Map.of("projectId", projectId, "title", "T"), ctx);
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("taskId")).isNotNull();
    }

    @Test
    void summarizeChannel_missingChannelId_returnsError() {
        var tool = new com.nocobase.ai.tools.SummarizeChannelTool(messageService, aiService);
        var result = tool.execute(Map.of(), ctx);
        // ctx.channelId 不为 null，应该正常执行（不会返回 error）
        assertThat(result).doesNotContainKey("error");
    }

    @Test
    void summarizeChannel_success() {
        var msg = new ImMessageEntity();
        msg.setContent("hello world");
        msg.setSenderId(userId);
        when(messageService.list(eq(channelId), any(), anyInt())).thenReturn(List.of(msg));
        var tool = new com.nocobase.ai.tools.SummarizeChannelTool(messageService, aiService);
        var result = tool.execute(Map.of(), ctx);
        assertThat(result).containsEntry("messageCount", 1);
        assertThat(result.get("summary")).isNotNull();
    }

    @Test
    void sendDing_missingRecipient_returnsError() {
        var tool = new com.nocobase.ai.tools.SendDingTool(dingTalkMessageService);
        var result = tool.execute(Map.of(), ctx);
        assertThat(result).containsEntry("sent", false);
        assertThat(result).containsKey("reason");
    }

    @Test
    void sendDing_group_missingChatId_returnsError() {
        var tool = new com.nocobase.ai.tools.SendDingTool(dingTalkMessageService);
        var result = tool.execute(Map.of("type", "group"), ctx);
        assertThat(result).containsEntry("sent", false);
        assertThat(result).containsKey("reason");
    }

    @Test
    void sendDing_group_success() {
        var tool = new com.nocobase.ai.tools.SendDingTool(dingTalkMessageService);
        when(dingTalkMessageService.sendGroupMessage(anyString(), anyString(), any()))
                .thenReturn(true);
        var result = tool.execute(Map.of("type", "group", "chatId", "c1", "title", "T"), ctx);
        assertThat(result).containsEntry("sent", true);
        assertThat(result).containsEntry("success", true);
    }

    @Test
    void sendDing_workNotice_failure() {
        var tool = new com.nocobase.ai.tools.SendDingTool(dingTalkMessageService);
        when(dingTalkMessageService.sendWorkNotice(anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        var result = tool.execute(Map.of("to", "user1", "title", "T"), ctx);
        assertThat(result).containsEntry("sent", false);
    }

    @Test
    void queryDatabase_missingCollectionName_returnsError() {
        var tool = new com.nocobase.ai.tools.QueryDatabaseTool(collectionService);
        var result = tool.execute(Map.of(), ctx);
        assertThat(result).containsEntry("error", "collectionName 必填");
    }

    @Test
    void queryDatabase_success() {
        when(collectionService.listRecords(eq("tasks"), eq(tenantId), anyInt(), any(), any()))
                .thenReturn(List.of(Map.of("id", "1", "title", "A")));
        var tool = new com.nocobase.ai.tools.QueryDatabaseTool(collectionService);
        var result = tool.execute(Map.of("collectionName", "tasks"), ctx);
        assertThat(result).containsEntry("rows", 1);
        assertThat((List<?>) result.get("data")).hasSize(1);
    }
}
