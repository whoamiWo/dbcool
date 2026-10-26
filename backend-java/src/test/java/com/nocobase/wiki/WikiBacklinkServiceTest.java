package com.nocobase.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE72：Wiki 反向链接（backlink）回归防线。
 *
 * <p>链路现状（审计确认可用）：页面正文里的 {@code [[slug]]} 由
 * {@code WikiPageService#parseBacklinks} 解析，经 {@code syncBacklinks}
 * 先 {@code deleteBySourcePageId} 再逐条 {@code save} 写入 {@code wiki_backlink}；
 * 前端 {@code NotionStyleEditor} 调 {@code GET /api/wiki/pages/{id}/backlinks} 渲染。
 *
 * <p>此前该链路**完全没有任何测试** —— 能用但无防线，改坏了也不会有人知道。
 *
 * <p>注意：链接语法是**双中括号** {@code [[slug]]}，markdown 形式的
 * {@code [标题](/wiki/slug)} **不会**被识别（实测确认）。
 */
class WikiBacklinkServiceTest {

    private WikiPageRepository pageRepository;
    private WikiBacklinkRepository backlinkRepository;
    private WikiPageService service;

    @BeforeEach
    void setUp() {
        pageRepository = mock(WikiPageRepository.class);
        backlinkRepository = mock(WikiBacklinkRepository.class);
        service = new WikiPageService(
                pageRepository,
                mock(WikiVersionRepository.class),
                mock(KnowledgeBaseService.class),
                mock(WikiPermissionService.class),
                backlinkRepository);
    }

    @Test
    void parseBacklinks_extractsDoubleBracketSlugs() {
        assertEquals(List.of("page-b"), service.parseBacklinks("见 [[page-b]] 详情"));
        assertEquals(List.of("a", "b"), service.parseBacklinks("[[a]] 与 [[b]]"));
        // 首尾空格要 trim
        assertEquals(List.of("page-b"), service.parseBacklinks("[[ page-b ]]"));
    }

    @Test
    void parseBacklinks_ignoresMarkdownLinks() {
        // markdown 链接不是双向链接语法 —— 这是实测中踩过的坑，锁死它
        assertEquals(List.of(), service.parseBacklinks("[Page B](/wiki/page-b)"));
    }

    @Test
    void parseBacklinks_handlesNullAndEmpty() {
        assertEquals(List.of(), service.parseBacklinks(null));
        assertEquals(List.of(), service.parseBacklinks(""));
        assertEquals(List.of(), service.parseBacklinks("没有链接"));
    }

    @Test
    void syncBacklinks_writesLinkForEachResolvedSlug() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        WikiPageEntity target = new WikiPageEntity();
        ReflectionTestUtils.setField(target, "id", targetId);

        when(pageRepository.findBySlugAndTenantId(eq("page-b"), anyString()))
                .thenReturn(Optional.of(target));

        service.syncBacklinks(sourceId, "tenant_default", "引用 [[page-b]]");

        // 先清空旧的，再写入新的
        verify(backlinkRepository, times(1)).deleteBySourcePageId(sourceId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<WikiBacklinkEntity> captor = ArgumentCaptor.forClass(WikiBacklinkEntity.class);
        verify(backlinkRepository, times(1)).save(captor.capture());

        WikiBacklinkEntity saved = captor.getValue();
        assertEquals(sourceId, saved.getSourcePageId());
        assertEquals(targetId, saved.getTargetPageId());
    }

    @Test
    void syncBacklinks_clearsExistingWhenContentHasNoLink() {
        UUID sourceId = UUID.randomUUID();
        service.syncBacklinks(sourceId, "tenant_default", "正文已删掉链接");

        verify(backlinkRepository, times(1)).deleteBySourcePageId(sourceId);
        verify(backlinkRepository, times(0)).save(any());
    }

    @Test
    void syncBacklinks_keepsPlaceholderForUnknownSlug() {
        // 目标页尚未创建时**仍写入**一条占位（targetId=null，仅记 targetSlug），
        // 这样目标页后续创建即可补上 —— 不是跳过。锁死这个设计意图。
        UUID sourceId = UUID.randomUUID();
        when(pageRepository.findBySlugAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.syncBacklinks(sourceId, "tenant_default", "指向 [[不存在]]");

        verify(backlinkRepository, times(1)).deleteBySourcePageId(sourceId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<WikiBacklinkEntity> captor = ArgumentCaptor.forClass(WikiBacklinkEntity.class);
        verify(backlinkRepository, times(1)).save(captor.capture());

        WikiBacklinkEntity saved = captor.getValue();
        assertTrue(saved.getTargetPageId() == null, "目标未创建时 targetId 应为 null");
        assertEquals("不存在", saved.getTargetSlug(), "应保留 slug 以便后续补链");
    }

    @Test
    void syncBacklinks_ignoresNullInputs() {
        service.syncBacklinks(null, "tenant_default", "[[x]]");
        service.syncBacklinks(UUID.randomUUID(), "tenant_default", null);
        verify(backlinkRepository, times(0)).deleteBySourcePageId(any());
    }

    // ============================================================
    //  PHASE130：锚文本 [[slug|anchor]] 与上下文摘录
    // ============================================================

    @Test
    void parseBacklinkRefs_extractsAnchorText() {
        List<WikiPageService.LinkRef> refs = service.parseBacklinkRefs("见 [[page-b|页面 B]] 详情");
        assertEquals(1, refs.size());
        assertEquals("page-b", refs.get(0).slug());
        assertEquals("页面 B", refs.get(0).anchorText());
    }

    @Test
    void parseBacklinkRefs_anchorIsNullForLegacySyntax() {
        List<WikiPageService.LinkRef> refs = service.parseBacklinkRefs("见 [[page-b]] 详情");
        assertEquals(1, refs.size());
        assertEquals("page-b", refs.get(0).slug());
        assertTrue(refs.get(0).anchorText() == null, "旧语法锚文本应为 null");
    }

    @Test
    void parseBacklinkRefs_trimsAnchorWhitespace() {
        List<WikiPageService.LinkRef> refs = service.parseBacklinkRefs("[[ page-b | 页面 B ]]");
        assertEquals("page-b", refs.get(0).slug());
        assertEquals("页面 B", refs.get(0).anchorText());
    }

    @Test
    void parseBacklinkRefs_handlesMixedSyntax() {
        List<WikiPageService.LinkRef> refs =
                service.parseBacklinkRefs("[[a|甲]] 与 [[b]] 以及 [[c|丙]]");
        assertEquals(3, refs.size());
        assertEquals("甲", refs.get(0).anchorText());
        assertTrue(refs.get(1).anchorText() == null);
        assertEquals("丙", refs.get(2).anchorText());
    }

    @Test
    void parseBacklinks_stillReturnsDistinctSlugsWithAnchorSyntax() {
        // 旧签名必须继续工作：锚文本语法下也应只返回 slug
        assertEquals(List.of("a", "b"),
                service.parseBacklinks("[[a|甲]] 与 [[b|乙]] 与 [[a|甲二]]"));
    }

    @Test
    void extractContext_returnsSurroundingTextWithoutMarkdown() {
        String content = "# 标题\n\n这里是一段 **重要** 的正文，见 [[page-b|页面 B]] 了解详情。";
        List<WikiPageService.LinkRef> refs = service.parseBacklinkRefs(content);
        String ctx = service.extractContext(content, refs.get(0).matchStart(), refs.get(0).matchEnd());

        assertTrue(ctx.contains("页面 B"), "上下文应包含链接显示文本: " + ctx);
        assertTrue(ctx.contains("重要"), "上下文应包含周边文字: " + ctx);
        assertTrue(!ctx.contains("#"), "应剥离 Markdown 标题标记: " + ctx);
        assertTrue(!ctx.contains("*"), "应剥离 Markdown 强调标记: " + ctx);
        assertTrue(!ctx.contains("[["), "不应残留原始链接语法: " + ctx);
    }

    @Test
    void extractContext_addsEllipsisWhenTruncatedAtBothEnds() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append("前");
        sb.append("[[page-b]]");
        for (int i = 0; i < 300; i++) sb.append("后");
        String content = sb.toString();

        List<WikiPageService.LinkRef> refs = service.parseBacklinkRefs(content);
        String ctx = service.extractContext(content, refs.get(0).matchStart(), refs.get(0).matchEnd());

        assertTrue(ctx.startsWith("…"), "左侧被截断应加省略号: " + ctx);
        assertTrue(ctx.endsWith("…"), "右侧被截断应加省略号: " + ctx);
        assertTrue(ctx.length() <= 900, "上下文长度应受控: " + ctx.length());
    }

    @Test
    void extractContext_handlesNullAndEmpty() {
        assertTrue(service.extractContext(null, 0, 0) == null);
        assertTrue(service.extractContext("", 0, 0) == null);
    }

    @Test
    void syncBacklinks_persistsAnchorTextAndContext() {
        UUID sourceId = UUID.randomUUID();
        when(pageRepository.findBySlugAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.syncBacklinks(sourceId, "tenant_default", "参见 [[page-b|页面 B]] 的说明");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<WikiBacklinkEntity> captor = ArgumentCaptor.forClass(WikiBacklinkEntity.class);
        verify(backlinkRepository, times(1)).save(captor.capture());

        WikiBacklinkEntity saved = captor.getValue();
        assertEquals("页面 B", saved.getAnchorText());
        assertTrue(saved.getContext() != null && saved.getContext().contains("页面 B"),
                "上下文应落库: " + saved.getContext());
    }

    @Test
    void syncBacklinks_deduplicatesSameSlugKeepingFirstOccurrence() {
        UUID sourceId = UUID.randomUUID();
        when(pageRepository.findBySlugAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.syncBacklinks(sourceId, "tenant_default",
                "第一次 [[page-b|首个锚文本]] 第二次 [[page-b|第二个锚文本]]");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<WikiBacklinkEntity> captor = ArgumentCaptor.forClass(WikiBacklinkEntity.class);
        verify(backlinkRepository, times(1)).save(captor.capture());
        assertEquals("首个锚文本", captor.getValue().getAnchorText(),
                "同一 slug 多次引用应保留首次出现的锚文本");
    }
}
