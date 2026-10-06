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

    @Test
    void T3_newAntiExample_noTenantIdDirectReturn() {
        // T3 reverse validation: simulate PHASE76 fixed TicketService#addNote original form
        // Get entity and return directly without tenantId check
        String badMethod = """
                public TicketEntity getTicket(java.util.UUID ticketId) {
                    TicketEntity ticket = ticketRepository.findById(ticketId).orElse(null);
                    return ticket;
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("bad/TicketService.java", badMethod);

        Set<String> entities = Set.of("TicketEntity");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.size() > 0,
                "T3 反向验证失败：审计器没有报出'无 tenantId 直返实体'漏洞！");
        assertEquals(1, violations.size(), "应只报一处违规");

        var v = violations.get(0);
        assertTrue(v.methodName().contains("getTicket"),
                "Should report getTicket() method missing ownership check");
    }

    @Test
    void T3_aclCheckShouldBeProtection() {
        // T2 new rule validation: ACL check should be considered protection
        String method = """
                public Map<String, Object> archivePage(java.util.UUID id, AuthenticatedUser user) {
                    aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page", Action.UPDATE);
                    WikiPageEntity entity = pageService.archive(id, user.userId(), user.tenantId());
                    return Map.of("code", 0);
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("test/WikiController.java", method);

        Set<String> entities = Set.of("WikiPageEntity");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.isEmpty(),
                "ACL check should be considered protection, false positive: " + violations);
    }

    @Test
    void T3_delegationWithParentheses() {
        // T2 fix: delegate pattern now supports user.tenantId() with parentheses
        String method = """
                public Map<String, Object> createPage(Map<String, Object> body, AuthenticatedUser user) {
                    WikiPageEntity entity = pageService.create(body, user.tenantId(), user.userId());
                    return Map.of("code", 0);
                }
                """;
        TenantIsolationAuditor.SourceFile fixture = new SourceFile("test/WikiController.java", method);

        Set<String> entities = Set.of("WikiPageEntity");
        TenantIsolationAuditor auditor = new TenantIsolationAuditor(entities);

        List<TenantIsolationAuditor.Violation> violations = auditor.audit(List.of(fixture));

        assertTrue(violations.isEmpty(),
                "Passing user.tenantId() on delegation should be protection: " + violations);
    }
}