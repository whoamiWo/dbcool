package com.nocobase.audit;

import com.nocobase.audit.TenantIsolationAuditor.SourceFile;
import com.nocobase.audit.TenantIsolationAuditor.Violation;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE74 / 🔒-8 审计器单元测试 + T4 反向验证。
 *
 * <p><b>T4 要求</b>：审计器必须能抓住"故意留的漏洞"。本测试构造一个"故意不校验租户"
 * 的方法作为 fixture，断言审计器必须把它报出来。漏掉这个反例 → 判定 T1 未实现。
 */
class TenantIsolationAuditorTest {

    @Test
    void T4_故意留的漏洞必须被报出() {
        // Fixture: 故意不校验租户归属的方法
        String badMethod = """
                public CollectionMetaEntity get(String collectionName) {
                    CollectionMetaEntity entity = repository.findById(collectionName);
                    return entity;
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("bad/CollectionService.java", badMethod);

        Set<String> entities = Set.of("CollectionMetaEntity");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.size() > 0,
                "T4 反向验证失败：审计器没有报出故意留的越权点！");
        assertEquals(1, violations.size(), "Fixture 只应报一处违规");

        var v = violations.get(0);
        assertTrue(v.methodName().contains("get"),
                "应报出 get() 方法缺失归属校验");
        assertTrue(v.reason().contains("租户实体"),
                "报告应指出引用了租户实体");
    }

    @Test
    void 正面样本不应报错() {
        // §1.3 正面样本：有归属校验
        String goodMethod = """
                public CollectionMetaEntity get(String collectionName) {
                    CollectionMetaEntity meta = get(collectionName);
                    if (!meta.getTenantId().equals(tenantId)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问");
                    }
                    return meta;
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("good/CollectionService.java", goodMethod);

        Set<String> entities = Set.of("CollectionMetaEntity");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.isEmpty(),
                "正面样本误报：" + violations);
    }

    @Test
    void 使用TenantContext调用也算防护() {
        String method = """
                public WikiPageReadDto read(String slug) {
                    String tenantId = TenantContext.currentTenantId();
                    return repository.findBySlugAndTenantId(slug, tenantId);
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("test/WikiService.java", method);

        Set<String> entities = Set.of("WikiPageReadDto");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.isEmpty(),
                "使用 TenantContext 应视为防护，不应误报：" + violations);
    }

    @Test
    void SpringData_派生查询含ByTenantId也算防护() {
        String method = """
                public WikiPage findBySlug(String slug) {
                    return repository.findBySlugAndTenantId(slug, tenantId);
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("test/WikiService.java", method);

        Set<String> entities = Set.of("WikiPage");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.isEmpty(),
                "Spring Data 派生查询已带租户过滤，不应误报：" + violations);
    }
}