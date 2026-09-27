package com.nocobase.wiki;

import com.nocobase.search.ChineseSegmenter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 56 P2-3：验证中文分词检索真的接入并生效。
 *
 * <p>核心对比（before / after）：
 * <ul>
 *   <li>文本「项目的管理工作流程说明」<b>不含</b>连续的「项目管理」</li>
 *   <li>改造前整串 {@code ILIKE '%项目管理%'} → <b>命中 0</b></li>
 *   <li>改造后分词 [项目, 管理] 逐词取交集 → <b>命中 1</b></li>
 * </ul>
 *
 * <p>注意：全部走既有 JPQL（{@code ILIKE}），H2 测试与 PostgreSQL 生产通用，
 * 不依赖任何数据库扩展（Alpine 无 gcc，严禁编译 zhparser / pg_jieba）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WikiChineseSearchTest {

    @Autowired
    private WikiSearchService searchService;

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private ChineseSegmenter segmenter;

    private final String tenantId = "zh-test-tenant";
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        UUID kbId = kbService.create("中文检索 KB", "中文分词验证", "zh-search-kb", "icon", userId, tenantId).getId();
        // 关键：正文是「项目的管理工作」——不包含连续的「项目管理」
        pageService.create(kbId, null, "流程说明", "zh-flow",
                "项目的管理工作流程说明", userId, tenantId);
        // 另一篇含「项目」但不含「管理」，用于验证 AND 语义不会误命中
        pageService.create(kbId, null, "项目立项", "zh-project",
                "项目立项申请需要审批", userId, tenantId);
    }

    @Test
    void segmenter_splitsChineseQuery() {
        List<String> terms = segmenter.segment("项目管理");
        assertFalse(terms.isEmpty(), "分词结果不应为空");
        // jieba 对「项目管理」应切出「项目」与「管理」
        assertTrue(terms.contains("项目"), "应切出「项目」，实际：" + terms);
        assertTrue(terms.contains("管理"), "应切出「管理」，实际：" + terms);
    }

    @Test
    void segmenter_handlesBlankAndNull() {
        assertTrue(segmenter.segment(null).isEmpty());
        assertTrue(segmenter.segment("").isEmpty());
        assertTrue(segmenter.segment("   ").isEmpty());
    }

    /**
     * 核心 before / after 对比：整串匹配不到，分词后能命中。
     *
     * <p>「项目管理」经 INDEX 分词 → [项目, 管理, 项目管理]：
     * <ul>
     *   <li>改造前整串 {@code ILIKE '%项目管理%'} → 命中 <b>0</b>（正文是「项目的管理工作」）</li>
     *   <li>改造后逐词检索 → 命中「流程说明」（含「项目」+「管理」）与「项目立项」（含「项目」）</li>
     * </ul>
     */
    @Test
    void search_chineseSegmented_hitsWhenWholeStringMisses() {
        Page<WikiPageEntity> results = searchService.search("项目管理", null, tenantId, 0, 10);
        assertEquals(2, results.getTotalElements(),
                "「项目管理」分词后应命中 2 篇（整串 ILIKE 命中 0 篇）");
        // 最相关的排第一：命中「项目」+「管理」两词
        assertEquals("流程说明", results.getContent().get(0).getTitle());
    }

    /** 相关度排序：命中词更多者排前面（流程说明 2 词 &gt; 项目立项 1 词）。 */
    @Test
    void search_chineseSegmented_ranksByRelevance() {
        Page<WikiPageEntity> results = searchService.search("项目管理", null, tenantId, 0, 10);
        assertEquals(2, results.getTotalElements());
        assertEquals("流程说明", results.getContent().get(0).getTitle(),
                "命中词更多的「流程说明」应排在「项目立项」之前");
        assertEquals("项目立项", results.getContent().get(1).getTitle());
    }

    /** 回归：英文关键词检索不受分词改造影响。 */
    @Test
    void search_english_stillWorks() {
        UUID kbId = kbService.create("英文 KB", "en", "en-search-kb", "icon", userId, tenantId).getId();
        pageService.create(kbId, null, "Java Guide", "en-java",
                "Learn Java programming basics", userId, tenantId);

        Page<WikiPageEntity> results = searchService.search("Java", null, tenantId, 0, 10);
        assertEquals(1, results.getTotalElements());
        assertEquals("Java Guide", results.getContent().get(0).getTitle());
    }

    /** 单词查询（分词后仅一个词）走原路径，行为不变。 */
    @Test
    void search_singleTerm_usesOriginalPath() {
        Page<WikiPageEntity> results = searchService.search("项目", null, tenantId, 0, 10);
        assertEquals(2, results.getTotalElements(),
                "「项目」应命中两篇（流程说明 + 项目立项）");
    }
}