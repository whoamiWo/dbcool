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

    // ---------- PHASE80 T2 降误报规则：正反两面都要有 ----------

    @Test
    void T2A_认证入口login应豁免() {
        // login 发生在建立租户上下文之前，无从校验归属 → 不该报
        String method = """
                public class AuthController {
                    public ResponseEntity<Map<String, Object>> login(LoginRequest request) {
                        UserEntity user = userService.findByUsername(request.getUsername());
                        return ResponseEntity.ok(Map.of("code", 0));
                    }
                }
                """;
        var fixture = new SourceFile("test/AuthController.java", method);
        var auditor = new TenantIsolationAuditor(Set.of("UserEntity"));

        assertTrue(auditor.audit(List.of(fixture)).isEmpty(),
                "认证入口 login 应豁免（尚无租户上下文），误报：" + auditor.audit(List.of(fixture)));
    }

    @Test
    void T2A_但同名login在非Auth类仍须报出() {
        // 白名单只在类名含 Auth 时生效 —— 其他类的同名方法不得被豁免（防滥用）
        String method = """
                public class UserAdminController {
                    public UserEntity login(String username) {
                        UserEntity user = userRepository.findByUsername(username);
                        return user;
                    }
                }
                """;
        var fixture = new SourceFile("test/UserAdminController.java", method);
        var auditor = new TenantIsolationAuditor(Set.of("UserEntity"));

        assertEquals(1, auditor.audit(List.of(fixture)).size(),
                "非 Auth 类的 login 不得豁免 —— 否则白名单会被滥用为掩盖手段");
    }

    @Test
    void T2B_委托给本类有校验的方法应视为防护() {
        // 真误报原型：WebhookSubscriptionController#delete 只调 mustGet(id)，
        // 而 mustGet 内部用 TenantContext.currentTenantId() 过滤
        String src = """
                public class WebhookSubscriptionController {
                    private WebhookSubscriptionEntity mustGet(java.util.UUID id) {
                        String tenant = TenantContext.currentTenantId();
                        WebhookSubscriptionEntity e = repository.findByIdAndTenantId(id, tenant);
                        return e;
                    }
                    public Map<String, Object> delete(java.util.UUID id) {
                        WebhookSubscriptionEntity e = mustGet(id);
                        repository.delete(e);
                        return Map.of("code", 0);
                    }
                }
                """;
        var fixture = new SourceFile("test/WebhookSubscriptionController.java", src);
        var auditor = new TenantIsolationAuditor(Set.of("WebhookSubscriptionEntity"));

        List<Violation> vs = auditor.audit(List.of(fixture));
        assertTrue(vs.stream().noneMatch(v -> v.methodName().equals("delete")),
                "delete 委托给了有校验的 mustGet，应视为防护，误报：" + vs);
    }

    @Test
    void T2B_委托链末端无校验仍须报出() {
        // 反向验证：委托规则不能被滥用 —— 若被调用的方法本身没校验，整条链仍要报
        String src = """
                public class PlaybookService {
                    private PlaybookEntity load(java.util.UUID id) {
                        PlaybookEntity p = repository.findById(id).orElseThrow();
                        return p;
                    }
                    public PlaybookEntity update(java.util.UUID id, String name) {
                        PlaybookEntity p = load(id);
                        p.setName(name);
                        return repository.save(p);
                    }
                }
                """;
        var fixture = new SourceFile("test/PlaybookService.java", src);
        var auditor = new TenantIsolationAuditor(Set.of("PlaybookEntity"));

        List<Violation> vs = auditor.audit(List.of(fixture));
        assertTrue(vs.stream().anyMatch(v -> v.methodName().equals("update")),
                "T2-B 反向验证失败：委托链末端 load 无校验，update 仍应被报出");
    }

    @Test
    void T2B_重载中未修的那个仍须报出_禁止用已修重载洗白() {
        // PHASE80 实测教训：WikiPageService#markTemplate 有两个重载，
        // (id,isTemplate) 未修（真越权）、(id,isTemplate,tenantId) 已修。
        // 若重载取 OR，已修的会把未修的洗白 —— 等于掩盖已确认漏洞。
        String src = """
                public class WikiPageService {
                    public WikiPageEntity markTemplate(java.util.UUID id, boolean isTemplate) {
                        WikiPageEntity e = pageRepository.findById(id).orElseThrow();
                        e.setIsTemplate(isTemplate);
                        return pageRepository.save(e);
                    }
                    public WikiPageEntity markTemplate(java.util.UUID id, boolean isTemplate, String tenantId) {
                        WikiPageEntity e = pageRepository.findById(id).orElseThrow();
                        if (!e.getTenantId().equals(tenantId)) throw new IllegalStateException();
                        return pageRepository.save(e);
                    }
                }
                """;
        var fixture = new SourceFile("test/WikiPageService.java", src);
        var auditor = new TenantIsolationAuditor(Set.of("WikiPageEntity"));

        List<Violation> vs = auditor.audit(List.of(fixture));
        assertEquals(1, vs.size(),
                "重载必须取 AND：未修的 markTemplate(id,isTemplate) 仍须报出，"
                        + "不能让已修重载洗白它（当前 " + vs.size() + " 条）");
    }

    @Test
    void T2B_调用无害helper不算委托防护() {
        // PHASE80 实测教训：ReactionService#remove 只调了 validateEmoji（emoji 格式校验，
        // 与租户完全无关）就被判为"已委托防护" → 洗白。委托目标自身必须确有归属校验。
        String src = """
                public class ReactionService {
                    public void remove(java.util.UUID messageId, java.util.UUID userId, String emoji) {
                        validateEmoji(emoji);
                        ImMessageReactionEntity r = reactionRepository.findByMessageId(messageId);
                        reactionRepository.delete(r);
                    }
                    private static void validateEmoji(String emoji) {
                        if (emoji == null || emoji.isBlank()) throw new IllegalArgumentException();
                    }
                }
                """;
        var fixture = new SourceFile("test/ReactionService.java", src);
        var auditor = new TenantIsolationAuditor(Set.of("ImMessageReactionEntity"));

        List<Violation> vs = auditor.audit(List.of(fixture));
        assertEquals(1, vs.size(),
                "调用与租户无关的格式校验 helper 不得视为委托防护（当前 " + vs.size() + " 条）");
        assertTrue(vs.get(0).methodName().equals("remove"));
    }

    @Test
    void T3_有user参数却不用tenantId必须报出() {
        // PHASE80 T3 核心反例：签名带了 AuthenticatedUser（意味着有租户信息可用），
        // 但全程不取 user.tenantId()、不做任何比对 → 典型"有工具却不用"的真越权
        String badMethod = """
                public TicketEntity updateStatus(java.util.UUID id, String status, AuthenticatedUser user) {
                    TicketEntity ticket = ticketRepository.findById(id).orElseThrow();
                    ticket.setStatus(status);
                    return ticketRepository.save(ticket);
                }
                """;
        var fixture = new SourceFile("bad/TicketService.java", badMethod);
        var auditor = new TenantIsolationAuditor(Set.of("TicketEntity"));

        List<Violation> vs = auditor.audit(List.of(fixture));
        assertEquals(1, vs.size(),
                "T3 反向验证失败：'有 user 参数却不用 user.tenantId()' 必须被报出（当前违规数 " + vs.size() + "）");
        assertTrue(vs.get(0).methodName().contains("updateStatus"));
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