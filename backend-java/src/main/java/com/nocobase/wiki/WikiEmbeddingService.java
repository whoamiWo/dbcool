package com.nocobase.wiki;

import com.nocobase.ai.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Wiki 文档向量嵌入服务 — 调用 Python AI 服务生成/检索向量。
 * 
 * <p>PHASE 57 AI 深度集成：
 * <ul>
 *   <li>调用 Python /api/ai/embedding 生成 768 维向量</li>
 *   <li>Java 直接执行 pgvector SQL 查询</li>
 *   <li>与现有 FTS 混合排序（语义结果优先 + FTS 补充）</li>
 * </ul>
 */
@Service
public class WikiEmbeddingService {

    private final WebClient aiWebClient;
    private final JdbcTemplate jdbcTemplate;
    private final WikiSearchService wikiSearchService;

    @Autowired
    public WikiEmbeddingService(WebClient.Builder builder, JdbcTemplate jdbcTemplate, WikiSearchService wikiSearchService) {
        this.aiWebClient = builder
                .baseUrl("http://localhost:8000")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.jdbcTemplate = jdbcTemplate;
        this.wikiSearchService = wikiSearchService;
    }

    /**
     * 生成文本的向量嵌入。
     *
     * @param text 输入文本
     * @param tenantId 租户 ID（用于路由）
     * @return 768 维浮点向量
     */
    public float[] generateEmbedding(String text, String tenantId) {
        try {
            EmbeddingResponse response = aiWebClient.post()
                    .uri("/api/ai/embedding")
                    .bodyValue(Map.of(
                            "text", text,
                            "tenant_id", tenantId,
                            "model", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
                    ))
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .block();

            if (response == null || response.embedding() == null) {
                throw new RuntimeException("AI 服务返回空向量");
            }
            // 将 List<Float> 转为 float[]
            List<Float> embeddingList = response.embedding();
            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                embedding[i] = embeddingList.get(i);
            }
            return embedding;
        } catch (Exception e) {
            // 降级：返回零向量（后续混合检索会忽略）
            return new float[768];
        }
    }

    /**
     * 混合检索：结合 FTS 和向量检索（Java 直接执行 pgvector SQL）。
     *
     * <p>流程:
     * <ol>
     *   <li>调用 Python /api/ai/embedding 生成 768 维向量</li>
     *   <li>在 PostgreSQL 执行原生 SQL: SELECT ... FROM wiki_page WHERE embedding IS NOT NULL
     *       ORDER BY embedding <=> :vector LIMIT :k</li>
     *   <li>与 FTS 结果合并（语义结果权重更高，FTS 作为补充召回）</li>
     * </ol>
     */
    public Page<WikiPageEntity> hybridSearch(
            String query, UUID kbId, String tenantId, int page, int size
    ) {
        // 生成向量（若失败则降级为零向量）
        float[] embedding = generateEmbedding(query, tenantId);
        boolean hasEmbedding = embedding != null && embedding.length > 0
                && !(embedding.length == 768 && isAllZero(embedding));

        // 1. 向量检索（原生 SQL，使用 JdbcTemplate）
        List<WikiPageEntity> semanticResults = Collections.emptyList();
        if (hasEmbedding) {
            semanticResults = executeVectorSearch(tenantId, embedding, size * 2);
        }

        // 2. FTS 结果（作为补充召回）
        Page<WikiPageEntity> ftsResults = wikiSearchService.search(query, kbId, tenantId, 0, size);

        // 3. 混合排序：语义结果优先 + FTS 补充（合并去重）
        LinkedHashMap<UUID, WikiPageEntity> ranked = new LinkedHashMap<>();
        // 语义结果先入（高权重）
        for (WikiPageEntity entity : semanticResults) {
            if (entity != null && entity.getId() != null) {
                ranked.put(entity.getId(), entity);
            }
        }
        // FTS 结果补充（去重）
        for (WikiPageEntity entity : ftsResults.getContent()) {
            if (!ranked.containsKey(entity.getId())) {
                ranked.put(entity.getId(), entity);
            }
        }

        List<WikiPageEntity> resultList = new ArrayList<>(ranked.values());
        int start = page * size;
        int end = Math.min(start + size, resultList.size());
        if (start >= resultList.size()) {
            return Page.empty(PageRequest.of(page, size));
        }
        return new PageImpl<>(
                resultList.subList(start, end),
                PageRequest.of(page, size),
                resultList.size()
        );
    }

    /**
     * 执行 pgvector 向量检索。
     */
    private List<WikiPageEntity> executeVectorSearch(String tenantId, float[] embedding, int limit) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        String vectorStr = sb.toString();

        String sql = "SELECT id, title, content FROM wiki_page "
                + "WHERE tenant_id = ? AND embedding IS NOT NULL "
                + "ORDER BY embedding <=> ?::vector LIMIT ?";

        RowMapper<WikiPageEntity> rowMapper = (rs, rowNum) -> {
            WikiPageEntity entity = new WikiPageEntity();
            entity.setId(UUID.fromString(rs.getString("id")));
            entity.setTitle(rs.getString("title"));
            entity.setContent(rs.getString("content"));
            return entity;
        };

        try {
            return jdbcTemplate.query(sql, rowMapper, tenantId, vectorStr, limit);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private boolean isAllZero(float[] arr) {
        for (float f : arr) {
            if (f != 0.0f) return false;
        }
        return true;
    }
}
