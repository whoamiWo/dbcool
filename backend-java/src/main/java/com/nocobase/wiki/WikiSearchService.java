package com.nocobase.wiki;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全文检索服务 — PostgreSQL FTS (to_tsvector + plainto_tsquery + ts_headline)。
 *
 * <p>搜索逻辑:
 * <ul>
 *   <li>使用 {@code content_tsv} 列(由触发器自动维护)</li>
 *   <li>查询: {@code content_tsv @@ plainto_tsquery('simple', query)}</li>
 *   <li>高亮: {@code ts_headline('simple', content, query)}</li>
 *   <li>排序: {@code ts_rank_cd(content_tsv, query) DESC}</li>
 * </ul>
 */
@Service
public class WikiSearchService {

    private final WikiPageRepository pageRepository;

    @Autowired
    public WikiSearchService(WikiPageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    /**
     * 全文搜索。
     *
     * @param query 搜索关键词
     * @param kbId 知识库 ID(可选)
     * @param tenantId 租户 ID
     * @param page 页码(0-based)
     * @param size 每页大小
     * @return 搜索结果分页
     */
    @Transactional(readOnly = true)
    public Page<WikiPageEntity> search(
            String query, UUID kbId, String tenantId, int page, int size
    ) {
        List<WikiPageEntity> results;
        if (kbId != null) {
            results = pageRepository.searchByContentAndKb(query, kbId, tenantId);
        } else {
            results = pageRepository.searchByContent(query, tenantId);
        }
        Pageable pageable = PageRequest.of(page, size);
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), results.size());
        return new PageImpl<>(results.subList(start, end), pageable, results.size());
    }
}