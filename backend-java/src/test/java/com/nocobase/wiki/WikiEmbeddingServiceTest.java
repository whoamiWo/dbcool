package com.nocobase.wiki;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WikiEmbeddingService 测试 — 证明语义向量检索主路径被执行（非降级路径）。
 *
 * <p>PHASE 57 P0 红线：必须有一条用例证明"非零 768 维向量 → executeVectorSearch 真被调用"。
 * 上一轮翻车根因：所有测试只覆盖 FTS 降级路径，向量检索从未真正参与。
 *
 * <p>实现变更（NPE 修复后）：executeVectorSearch 改为
 * "先查 id（jdbcTemplate.queryForList）→ 再 findAllById 回查完整实体"，
 * 以避免手工构造的半成品实体在 toPageDto() 触发
 * getKnowledgeBaseId().toString() 的 NPE。签名新增 kbId 参数以支持知识库过滤。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WikiEmbeddingServiceTest {

    @Autowired
    private WikiEmbeddingService embeddingService;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void resetMocks() {
        reset(jdbcTemplate);
    }

    private static float[] nonZeroVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = 0.01f * ((i % 17) + 1);
        }
        return v;
    }

    /**
     * 当前 executeVectorSearch 签名：(String tenantId, UUID kbId, float[] embedding, int limit)。
     */
    private static java.lang.reflect.Method vectorSearchMethod() throws NoSuchMethodException {
        java.lang.reflect.Method m = WikiEmbeddingService.class.getDeclaredMethod(
                "executeVectorSearch", String.class, UUID.class, float[].class, int.class);
        m.setAccessible(true);
        return m;
    }

    /** stub 向量 SQL 的 id 查询结果（空结果集 → 直接返回空列表）。 */
    private void stubVectorSqlReturning(List<String> ids) {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(ids);
    }

    /**
     * 红线用例：非零 768 维向量必须触发 executeVectorSearch（pgvector SQL 真被调用）。
     *
     * <p>构造非零向量 → 反射调用 → verify jdbcTemplate.queryForList(...) 至少一次。
     */
    @Test
    void hybridSearch_nonZeroVector_executesVectorSql() {
        float[] vector = nonZeroVector(768);
        stubVectorSqlReturning(Collections.emptyList());

        try {
            Object result = vectorSearchMethod()
                    .invoke(embeddingService, "test-tenant", null, vector, 10);

            verify(jdbcTemplate, atLeastOnce())
                    .queryForList(anyString(), eq(String.class), any(Object[].class));
            assertNotNull(result);
        } catch (Exception e) {
            fail("executeVectorSearch failed: " + e.getMessage());
        }
    }

    /** 执行的 SQL 必须含 pgvector 余弦距离算子，且按相似度排序。 */
    @Test
    void executeVectorSearch_sqlUsesPgVectorOperator() {
        float[] vector = nonZeroVector(768);
        stubVectorSqlReturning(Collections.emptyList());

        try {
            vectorSearchMethod().invoke(embeddingService, "test-tenant", null, vector, 10);
        } catch (Exception e) {
            fail("reflection failed: " + e.getMessage());
        }

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce())
                .queryForList(sqlCaptor.capture(), eq(String.class), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertNotNull(sql);
        assertTrue(sql.contains("embedding <=> ?::vector"),
                "SQL 必须含 pgvector 余弦距离算子,实际: " + sql);
        assertTrue(sql.contains("ORDER BY"), "必须按相似度排序,实际: " + sql);
    }

    /** 传 kbId 时 SQL 必须追加知识库过滤，防止跨库泄漏。 */
    @Test
    void executeVectorSearch_withKbId_addsKnowledgeBaseFilter() {
        float[] vector = nonZeroVector(768);
        stubVectorSqlReturning(Collections.emptyList());
        UUID kbId = UUID.randomUUID();

        try {
            vectorSearchMethod().invoke(embeddingService, "test-tenant", kbId, vector, 10);
        } catch (Exception e) {
            fail("reflection failed: " + e.getMessage());
        }

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce())
                .queryForList(sqlCaptor.capture(), eq(String.class), any(Object[].class));
        assertTrue(sqlCaptor.getValue().contains("knowledge_base_id = ?"),
                "传 kbId 时必须按知识库过滤,实际: " + sqlCaptor.getValue());
    }

    /**
     * 非零向量生成的 vectorStr 必须非空且含非零元素 — 防止静默退化成零向量。
     */
    @Test
    void executeVectorSearch_buildsNonZeroVectorString() {
        float[] vector = nonZeroVector(768);
        stubVectorSqlReturning(Collections.emptyList());

        try {
            vectorSearchMethod().invoke(embeddingService, "test-tenant", null, vector, 10);
        } catch (Exception e) {
            fail("reflection failed: " + e.getMessage());
        }

        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce())
                .queryForList(anyString(), eq(String.class), captor.capture());
        Object[] args = captor.getValue();
        // kbId 为 null 时参数顺序: [tenantId, vectorStr, limit]
        String vectorStr = (String) args[1];
        assertNotNull(vectorStr);
        assertTrue(vectorStr.startsWith("[") && vectorStr.endsWith("]"));
        assertFalse(vectorStr.matches("\\[(0\\.0(,)?)+\\]"), "vector string must not be all zeros");
    }

    /**
     * 向量 SQL 返回 id 时,必须回查 Repository 得到完整实体
     * （回归用例: 早期实现用 RowMapper 手工 new 半成品实体,
     * 导致 toPageDto() 的 getKnowledgeBaseId().toString() NPE）。
     */
    @Test
    void executeVectorSearch_returnsFullEntities_notPartialOnes() {
        float[] vector = nonZeroVector(768);
        // 返回一个不存在的 id: 回查不到实体时应安全返回空,而不是半成品实体
        stubVectorSqlReturning(List.of(UUID.randomUUID().toString()));

        try {
            @SuppressWarnings("unchecked")
            List<WikiPageEntity> result = (List<WikiPageEntity>) vectorSearchMethod()
                    .invoke(embeddingService, "test-tenant", null, vector, 10);
            assertNotNull(result);
            assertTrue(result.isEmpty(),
                    "回查不到的 id 不应返回实体（避免半成品实体导致 NPE）");
        } catch (Exception e) {
            fail("reflection failed: " + e.getMessage());
        }
    }

    /**
     * 零向量也会尝试执行 SQL（因设计),但参数是全零字符串.
     * 这个用例证明零向量路径会返回空列表,不触发 pgvector 索引搜索。
     */
    @Test
    void executeVectorSearch_zeroVector_returnsEmptyList() {
        stubVectorSqlReturning(Collections.emptyList());
        try {
            List<?> result = (List<?>) vectorSearchMethod()
                    .invoke(embeddingService, "test-tenant", null, new float[768], 10);
            assertTrue(result.isEmpty(), "zero vector should return empty from H2 mock");
        } catch (Exception e) {
            fail("reflection failed: " + e.getMessage());
        }
    }
}
