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

    /**
     * PHASE 57: 服务间调用令牌(与 Python 侧 internal_service_token 一致)。
     *
     * <p>Java → Python `/api/ai/embedding` 属内部调用,需携带此令牌;
     * 未配置则请求不带 Authorization,Python 侧会按用户 JWT 校验并拒绝(401),
     * 此时向量降级为零、混合检索退化为纯 FTS(不阻断业务)。
     */
    @org.springframework.beans.factory.annotation.Value("${ai.internal-token:}")
    private String internalToken;

    /**
     * 用于把向量检索得到的 id 回查成完整实体（避免手工构造实体导致字段缺失）。
     * 字段注入而非构造器注入：保持构造签名稳定，不影响既有单元测试。
     */
    @Autowired
    private WikiPageRepository wikiPageRepository;

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
            var request = aiWebClient.post()
                    .uri("/api/ai/embedding");
            // 携带服务间令牌（Python 侧 internal_service_token 校验）
            if (internalToken != null && !internalToken.isBlank()) {
                request = request.header(
                        org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + internalToken);
            }
            EmbeddingResponse response = request
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
            semanticResults = executeVectorSearch(tenantId, kbId, embedding, size * 2);
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
     * 执行 pgvector 向量检索，返回**完整**实体（保持向量相似度顺序）。
     *
     * <p>注意：早期实现用 RowMapper 手工 new 了只有 id/title/content 的“半成品”
     * 实体，导致 Controller 的 {@code toPageDto()} 调用
     * {@code getKnowledgeBaseId().toString()} 时 NPE（500）。
     * 此处只取 id，再回查 Repository 得到完整实体，避免字段缺失。
     */
    private List<WikiPageEntity> executeVectorSearch(
            String tenantId, UUID kbId, float[] embedding, int limit
    ) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        String vectorStr = sb.toString();

        StringBuilder sql = new StringBuilder(
                "SELECT id FROM wiki_page WHERE tenant_id = ? AND embedding IS NOT NULL ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (kbId != null) {
            sql.append("AND knowledge_base_id = ? ");
            args.add(kbId);
        }
        sql.append("ORDER BY embedding <=> ?::vector LIMIT ?");
        args.add(vectorStr);
        args.add(limit);

        List<UUID> orderedIds;
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    sql.toString(), String.class, args.toArray());
            orderedIds = new ArrayList<>();
            for (String r : rows) {
                try {
                    orderedIds.add(UUID.fromString(r));
                } catch (IllegalArgumentException ignore) {
                    // 非法 uuid 跳过
                }
            }
        } catch (Exception e) {
            // pgvector 未启用 / SQL 异常：降级为空，交由 FTS 兜底
            return Collections.emptyList();
        }

        if (orderedIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<UUID, WikiPageEntity> byId = new HashMap<>();
        for (WikiPageEntity e : wikiPageRepository.findAllById(orderedIds)) {
            byId.put(e.getId(), e);
        }
        List<WikiPageEntity> ordered = new ArrayList<>();
        for (UUID id : orderedIds) {
            WikiPageEntity e = byId.get(id);
            if (e != null) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    private boolean isAllZero(float[] arr) {
        for (float f : arr) {
            if (f != 0.0f) return false;
        }
        return true;
    }
}
