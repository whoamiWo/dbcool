package com.nocobase.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多租户归属校验审计器（PHASE74 / 🔒-8）。
 *
 * <p><b>为什么需要</b>：本项目已有 Schema 级隔离（49 个实体带 {@code tenantId} 字段），
 * 但"数据带了 tenant_id"不等于"每个接口读取时校验归属"。漏一次校验就是一个跨租户
 * 越权读/写，而且这类代码看起来完全正常，只是少了句 {@code equals(tenantId)}，
 * Code Review 极易漏看。本审计器把这一检查自动化并接入 {@code mvn test} 门禁。
 *
 * <p><b>实现方式</b>：源码扫描（无外部依赖，方案 B）。刻意不引入 ArchUnit ——
 * Maven 离线模式无法下载新依赖。
 *
 * <p><b>判定规则</b>：一个方法判为"缺归属校验"需同时满足：
 * <ol>
 *   <li><b>访问了租户数据</b>：方法体引用了带 {@code tenantId} 字段的实体类型；</li>
 *   <li><b>方法内没有任何归属校验</b>：不含下列任一模式 ——
 *       <ul>
 *         <li>{@code TenantContext.currentTenantId()} 调用</li>
 *         <li>实体归属字段与 tenantId 的比对（{@code getTenantId().equals(...)} /
 *             {@code Objects.equals(...)} / {@code X.equals(tenantId)}）</li>
 *         <li>以 tenantId 作为查询条件下传给 Repository
 *             （Spring Data 派生查询 {@code findByTenantIdAndXxx} / 显式 {@code tenant_id =}）</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>[PHASE80 T2] 两条降误报规则</b>（均配反例守着，见测试）：
 * <ul>
 *   <li><b>T2-A 认证入口豁免</b>：类名含 {@code Auth} 且方法名属于登录/刷新类白名单 →
 *       此刻尚无租户上下文，无从校验（不是漏写）。白名单刻意不含
 *       {@code callback}/{@code handle} 等宽泛名字，避免掩盖真漏洞。</li>
 *   <li><b>T2-B 委托隔离</b>：方法自身没写校验，但调用了<b>本类中</b>有校验的方法 →
 *       沿调用链递归判定为已防护。只认本类调用；跨类调用仍需靠"传 tenantId 实参"规则。</li>
 * </ul>
 *
 * <p><b>重载判定取 AND（PHASE80 教训）</b>：同名重载<b>必须全部</b>有校验才算安全。
 * 曾取 OR，结果 {@code WikiPageService#markTemplate} 被洗白 ——
 * 它有两个重载，{@code (id, isTemplate)} 无校验（真越权），
 * {@code (id, isTemplate, tenantId)} 有校验，OR 会用"修好的"掩盖"没修的"。
 * 判据：<b>只要存在一个无校验的重载，该名字就必须继续报</b>。
 *
 * <p><b>设计为纯函数</b>：{@link #audit(Collection)} 只依赖入参（源码文本 + 租户实体名集合），
 * 不读文件系统、不依赖 Spring 容器，因此可被单测用 fixture 直接验证
 * （含"故意不校验"的反例，见 {@code TenantIsolationAuditorTest}）。
 */
public final class TenantIsolationAuditor {

    /** 审计目标：Controller 与 Service。 */
    private static final Set<String> AUDITED_SUFFIXES = Set.of("Controller.java", "Service.java");

    /** 归属校验的识别模式 —— 任一命中即认为该方法有防护。 */
    private static final List<Pattern> PROTECTION_PATTERNS = List.of(
            // TenantContext.currentTenantId()
            Pattern.compile("TenantContext\\s*\\.\\s*currentTenantId\\s*\\("),
            // 实体归属字段与入参比对：getTenantId().equals(x) / x.getTenantId().equals(y)
            Pattern.compile("getTenantId\\s*\\(\\s*\\)\\s*\\.\\s*equals\\s*\\("),
            // Objects.equals(a.getTenantId(), b)
            Pattern.compile("Objects\\s*\\.\\s*equals\\s*\\([^)]*getTenantId"),
            // 反向比对：something.equals(tenantId) —— 实体或请求参数与 tenantId 比较
            Pattern.compile("\\.\\s*equals\\s*\\(\\s*tenantId\\w*\\s*\\)"),
            Pattern.compile("\\.\\s*equals\\s*\\(\\s*currentTenantId"),
            // user.tenantId().equals(entity.getTenantId()) 及其反向 —— 认证用户与实体比对
            Pattern.compile("user\\.tenantId\\s*\\(\\s*\\)\\s*\\.\\s*equals\\s*\\("),
            Pattern.compile("tenantId\\s*\\(\\s*\\)\\s*\\.\\s*equals\\s*\\(\\s*[a-z]\\w*\\.getTenantId"),
            // Spring Data 派生查询：查询条件已带租户过滤（DB 层隔离）
            Pattern.compile("\\bfind\\w*ByTenantId"),
            Pattern.compile("\\b\\w*By\\w*AndTenantId\\w*\\s*\\("),
            Pattern.compile("query.*tenant_id|tenant_id\\s*=|setTenantId\\s*\\("),
            // [PHASE78 R1] TenantContext 模式：通过 ThreadLocal 获取当前租户（JWT 过滤器写入）
            Pattern.compile("TenantContext\\.currentTenantId\\s*\\(\\s*\\)"),
            // [PHASE78 R1] Principal 提取租户：AuthenticatedUser.tenantId() 被调用（Controller 层委托给 Service）
            Pattern.compile("\\w+\\.tenantId\\s*\\(\\s*\\)"),
            // [PHASE79 T2] ACL 校验即防护：aclEnforcer.assertCan/checkCan 等显式权限校验带 tenantId
            Pattern.compile("aclEnforcer\\s*\\.\\s*(assertCan|checkCan|hasPermission)\\s*\\("),
            // [PHASE79 T2] 委托隔离（增强版）：方法调用中包含 tenantId 作为实参
            // 匹配形如 get(id, tenantId)、save(entity, tenantId) 等，其中 tenantId 是独立变量名
            // 用 \\btenantId\\b 确保是完整变量名，不是 tenantIdXxx 的一部分
            Pattern.compile("\\([^)]*\\btenantId\\b[^)]*\\)"),
            Pattern.compile("\\([^)]*\\.tenantId\\s*\\([^)]*\\)")
    );

    /**
     * 方法签名：访问修饰符 + 返回类型 + 名字 + 参数列表（不含 getter/setter/构造器）。
     *
     * <p>收录 {@code private} 是 [PHASE80 T2-B] 的需要：委托目标常常是 private helper
     * （如 {@code mustGet(id)}），不收录就看不到校验在隔壁方法里。
     * 但 private 方法<b>自身</b>不作为审计目标上报（见 {@code MethodInfo#auditable}），
     * 以免审计范围被悄悄扩大、基线暴涨。
     */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "^[ \\t]*(public|protected|private)[ \\t]+(?:final[ \\t]+)?"
                    + "(?!class\\b)(?:static[ \\t]+)?[\\w<>\\[\\],.\\s?]*?"
                    + "(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]+)?\\{",
            Pattern.MULTILINE);

    /**
     * [PHASE80 T2-A] 认证入口豁免。
     *
     * <p>登录 / 令牌刷新发生在<b>建立租户上下文之前</b> —— 此刻还不知道用户属于哪个租户，
     * 因此"校验归属"在语义上无法成立（不是漏写，是无从校验）。
     *
     * <p>刻意收得很窄：必须<b>类名含 Auth</b> 且方法名命中下列白名单才豁免。
     * 不加 {@code callback} / {@code handle} 之类宽泛名字 —— 那些可能是真漏洞
     * （如 {@code WeComController#callback} 仍在基线里待查），放进白名单等于掩盖。
     */
    private static final Set<String> AUTH_ENTRY_METHODS = Set.of(
            "login", "logout", "refresh", "register", "authenticate",
            "forgotPassword", "resetPassword", "verify");

    /** 明显的纯包装方法，不参与审计（降低误报）。 */
    private static final Set<String> IGNORED_METHOD_NAMES = Set.of(
            "toString", "equals", "hashCode", "getClass");

    /** 调用表达式中的关键字，不算方法调用（避免 if( / for( 被当成调用）。 */
    private static final Set<String> NOT_A_CALL = Set.of(
            "if", "for", "while", "switch", "catch", "try", "do", "else",
            "return", "new", "synchronized", "throw", "assert");

    /** @param auditable 是否作为审计目标上报（public/protected 才是；private 仅参与委托链判定）。 */
    private record MethodInfo(String name, String params, String body, int offset, boolean auditable) {}

    public record Violation(String file, String className, String methodName,
                            int line, String reason) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + className + "#" + methodName + "  → " + reason;
        }
    }

    public record SourceFile(String path, String content) {}

    private final Set<String> tenantEntityNames;

    public TenantIsolationAuditor(Set<String> tenantEntityNames) {
        this.tenantEntityNames = Set.copyOf(tenantEntityNames);
    }

    public List<Violation> audit(Collection<SourceFile> files) {
        List<Violation> violations = new ArrayList<>();
        for (SourceFile file : files) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            if (!AUDITED_SUFFIXES.stream().anyMatch(name::endsWith)) {
                continue;
            }
            violations.addAll(auditFile(file));
        }
        return violations;
    }

    /**
     * 两遍扫描：先建本类方法表（含各自 body），再判定每个方法是否有防护。
     * 第二遍需要方法表，是因为 [PHASE80 T2-B] 委托规则要"顺着调用链找校验"。
     */
    private List<Violation> auditFile(SourceFile file) {
        String content = file.content();
        String className = extractClassName(content);
        List<MethodInfo> methods = extractMethods(content);

        Map<String, MethodInfo> byName = new LinkedHashMap<>();
        for (MethodInfo mi : methods) {
            byName.putIfAbsent(mi.name(), mi);
        }

        // 委托链的判定依据：该方法**自身**对租户数据确有归属校验。
        //
        // 两个刻意的设计（都来自实测踩坑）：
        // 1) 只认"有校验"，不认"无害" —— 若把"不触碰租户数据的方法"也算作可委托目标，
        //    ReactionService#remove 会因调用了 validateEmoji(emoji)（纯 emoji 格式校验，
        //    与租户无关）而被判为已防护，直接洗白。
        // 2) 同名重载取 **AND** —— 所有重载都有校验，该名字才算安全。不能取 OR：
        //    WikiPageService#markTemplate 有两个重载，(id,isTemplate) 无校验（真越权）、
        //    (id,isTemplate,tenantId) 有校验，取 OR 会用"修好的"掩盖"没修的"。
        Map<String, Boolean> hasCheck = new HashMap<>();
        for (MethodInfo mi : methods) {
            // 只看"自身是否确有校验"，不要求 body 里出现实体名 ——
            // 委托目标的类型常在签名/泛型里（如 WebhookSubscriptionController#mustGet
            // 返回 WebhookSubscriptionEntity，但 body 内不出现该词），加上
            // touchesTenantData 条件会漏判，导致真误报消不掉。
            boolean prot = hasOwnershipCheck(mi.body());
            hasCheck.merge(mi.name(), prot, Boolean::logicalAnd);
        }

        List<Violation> out = new ArrayList<>();
        for (MethodInfo mi : methods) {
            if (!mi.auditable() // private 只参与委托链，不作为审计目标上报
                    || IGNORED_METHOD_NAMES.contains(mi.name())
                    || isGetterOrSetter(mi.name(), mi.params())) {
                continue;
            }
            if (isAuthEntry(className, mi.name())) {
                continue; // [T2-A] 认证入口：尚无租户上下文
            }
            if (!touchesTenantData(mi.body())) {
                continue;
            }
            if (isProtected(mi, byName, hasCheck)) {
                continue; // 自身有校验，或委托给了本类中确有校验的方法 [T2-B]
            }
            out.add(new Violation(file.path(), className, mi.name(),
                    lineOf(content, mi.offset()),
                    "引用了租户实体 " + referencedEntities(mi.body())
                            + " 但方法内无归属校验（既未比对 tenantId，"
                            + "也未以 tenantId 作为查询条件，"
                            + "也未委托给本类中有校验的方法）"));
        }
        return out;
    }

    /**
     * [PHASE80 T2-B] 委托隔离：方法自身没写校验，但调用了<b>本类中</b>有校验的方法，
     * 则视为已防护（沿调用链递归，深度受 visited 保护防环）。
     *
     * <p>典型真误报：{@code WebhookSubscriptionController#delete} 只写
     * {@code mustGet(id)}，而 {@code mustGet} 内部用 {@code TenantContext.currentTenantId()}
     * 过滤 —— 校验确实存在，只是不在同一方法体里。
     *
     * <p>只认<b>本类</b>调用（{@code byName} 查得到）：跨类调用无法静态确认对方是否校验，
     * 那要靠"传 tenantId 实参"的既有规则（PROTECTION_PATTERNS 末两条）覆盖。
     */
    private boolean isProtected(MethodInfo mi, Map<String, MethodInfo> byName,
                                Map<String, Boolean> hasCheck) {
        // 自身层面：不触碰租户数据（无害）或自己写了校验
        if (!touchesTenantData(mi.body()) || hasOwnershipCheck(mi.body())) {
            return true;
        }
        // 委托层面：调用了本类中**确有归属校验**的方法（单层，不递归 ——
        // 长链会让"末端有校验"洗白中间层的裸读，风险大于收益）
        for (String callee : callees(mi.body())) {
            if (byName.containsKey(callee) && Boolean.TRUE.equals(hasCheck.get(callee))) {
                return true;
            }
        }
        return false;
    }

    /** [T2-A] 认证入口：类名含 Auth + 方法名在白名单内 → 无法校验归属，豁免。 */
    private static boolean isAuthEntry(String className, String methodName) {
        return className.contains("Auth") && AUTH_ENTRY_METHODS.contains(methodName);
    }

    /** 提取源码中所有方法（签名 + 花括号配平后的 body）。 */
    private static List<MethodInfo> extractMethods(String content) {
        List<MethodInfo> out = new ArrayList<>();
        Matcher m = METHOD_SIGNATURE.matcher(content);
        while (m.find()) {
            String body = extractBody(content, m.end() - 1);
            if (body == null) {
                continue;
            }
            boolean auditable = !"private".equals(m.group(1));
            out.add(new MethodInfo(m.group(2), m.group(3), body, m.start(), auditable));
        }
        return out;
    }

    /** 方法体内<b>本类方法</b>风格的调用名（排除关键字与 {@code x.foo()} 跨类调用）。 */
    private static List<String> callees(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w.])([a-zA-Z_]\\w*)\\s*\\(").matcher(body);
        while (m.find()) {
            String n = m.group(1);
            if (!NOT_A_CALL.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 收集源码中出现的租户实体名（用于报告与规则可解释性）。 */
    private String referencedEntities(String body) {
        List<String> hits = new ArrayList<>();
        for (String entity : tenantEntityNames) {
            if (Pattern.compile("\\b" + Pattern.quote(entity) + "\\b").matcher(body).find()) {
                hits.add(entity);
            }
        }
        return hits.isEmpty() ? "(未识别)" : String.join("/", hits);
    }

    /** 规则 1：方法体是否访问了租户数据。 */
    private boolean touchesTenantData(String body) {
        for (String entity : tenantEntityNames) {
            if (Pattern.compile("\\b" + Pattern.quote(entity) + "\\b").matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    /** 规则 2：方法体是否存在任一归属校验模式。 */
    private boolean hasOwnershipCheck(String body) {
        for (Pattern p : PROTECTION_PATTERNS) {
            if (p.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private static String extractClassName(String content) {
        Matcher m = Pattern.compile("(?:class|interface)\\s+(\\w+)").matcher(content);
        return m.find() ? m.group(1) : "(unknown)";
    }

    private static boolean isGetterOrSetter(String name, String params) {
        boolean noArgs = params.isBlank();
        boolean getter = noArgs && name.startsWith("get") && name.length() > 3
                && Character.isUpperCase(name.charAt(3));
        boolean isIs = noArgs && name.startsWith("is") && name.length() > 2
                && Character.isUpperCase(name.charAt(2));
        boolean setter = params.trim().split(",").length == 1 && name.startsWith("set")
                && name.length() > 3 && Character.isUpperCase(name.charAt(3));
        return getter || isIs || setter;
    }

    /** 从 openBrace 位置开始做花括号配平，取出方法体。 */
    private static String extractBody(String content, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(openBrace + 1, i);
                }
            }
        }
        return null;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}