package com.nocobase.wiki;

import com.nocobase.search.ChineseSegmenter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全文检索服务 —— 中文走应用层分词，其余保持 ILIKE 子串匹配。
 *
 * <p>PHASE 56 P2-3 改造点：
 * <ul>
 *   <li>查询串先用 {@link ChineseSegmenter}（jieba，纯 Java，INDEX 模式）分词</li>
 *   <li>多词时<b>逐词检索取并集</b>（OR 语义），并<b>按命中词数降序排序</b>体现相关度：
 *       「项目管理」→ [项目, 管理, 项目管理]，
 *       可命中正文为「项目的管理工作」的文档，而旧的整串
 *       {@code ILIKE '%项目管理%'} <b>命中 0</b></li>
 *   <li>并集为空时回退整串 ILIKE，保证召回不低于改造前</li>
 *   <li>复用既有 {@code searchByContent / searchByContentAndKb}（JPQL ILIKE），
 *       H2 测试与 PostgreSQL 生产<b>都可执行</b>，不依赖数据库扩展
 *       （Alpine 无 gcc，严禁编译 zhparser / pg_jieba）</li>
 * </ul>
 *
 * <p><b>为什么是 OR 而非 AND</b>：INDEX 模式会同时产出不同粒度的词
 * （[项目, 管理, 项目管理]），AND 要求文档同时含全部粒度，反而漏召回；
 * OR + 命中数排序既能召回，又能把最相关的排前面。
 */
@Service
public class WikiSearchService {

    private final WikiPageRepository pageRepository;
    private final ChineseSegmenter segmenter;

    @Autowired
    public WikiSearchService(WikiPageRepository pageRepository, ChineseSegmenter segmenter) {
        this.pageRepository = pageRepository;
        this.segmenter = segmenter;
    }

    /**
     * 全文搜索。
     *
     * @param query 搜索关键词
     * @param kbId 知识库 ID(可选)
     * @param tenantId 租户 ID
     * @param page 页码(0-based)
     * @param size 每页大小
     * @return 搜索结果分页（按相关度降序）
     */
    @Transactional(readOnly = true)
    public Page<WikiPageEntity> search(
            String query, UUID kbId, String tenantId, int page, int size
    ) {
        List<WikiPageEntity> results = queryByTerms(query, kbId, tenantId);

        Pageable pageable = PageRequest.of(page, size);
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), results.size());
        List<WikiPageEntity> window = start <= end ? results.subList(start, end) : List.of();
        return new PageImpl<>(window, pageable, results.size());
    }

    /** 按分词结果检索：多词取并集并按命中词数排序；并集为空回退整串。 */
    private List<WikiPageEntity> queryByTerms(String query, UUID kbId, String tenantId) {
        if (query == null || query.isBlank()) {
            return byKeyword("", kbId, tenantId);
        }
        List<String> terms = segmenter.segment(query);
        // 单词（或分词无结果）走原逻辑，行为与改造前一致
        if (terms.size() <= 1) {
            return byKeyword(query, kbId, tenantId);
        }
        List<WikiPageEntity> ranked = unionRankedByHitCount(terms, kbId, tenantId);
        return ranked.isEmpty() ? byKeyword(query, kbId, tenantId) : ranked;
    }

    /**
     * 逐词检索取并集，按「命中了多少个不同的词」降序排序。
     * 命中数相同时保持首次出现顺序（JPQL 已按 updatedAt DESC）。
     */
    private List<WikiPageEntity> unionRankedByHitCount(List<String> terms, UUID kbId, String tenantId) {
        Map<UUID, Integer> hitCount = new LinkedHashMap<>();
        Map<UUID, WikiPageEntity> entities = new HashMap<>();
        for (String term : terms) {
            List<WikiPageEntity> hits = byKeyword(term, kbId, tenantId);
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            Set<UUID> seenInThisTerm = new HashSet<>();
            for (WikiPageEntity p : hits) {
                if (p == null || p.getId() == null) {
                    continue;
                }
                entities.putIfAbsent(p.getId(), p);
                // 同一个词在一篇文档内只计一次
                if (seenInThisTerm.add(p.getId())) {
                    hitCount.merge(p.getId(), 1, Integer::sum);
                }
            }
        }
        if (hitCount.isEmpty()) {
            return List.of();
        }
        return hitCount.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .map(e -> entities.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    /** 整串关键词检索（复用既有 JPQL，H2 / PostgreSQL 通用）。 */
    private List<WikiPageEntity> byKeyword(String keyword, UUID kbId, String tenantId) {
        List<WikiPageEntity> hits = kbId != null
                ? pageRepository.searchByContentAndKb(keyword, kbId, tenantId)
                : pageRepository.searchByContent(keyword, tenantId);
        return hits == null ? List.of() : hits;
    }
}