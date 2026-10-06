package com.nocobase.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    /** 方法签名：访问修饰符 + 返回类型 + 名字 + 参数列表（不含 getter/setter/构造器）。 */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "^[ \\t]*(?:public|protected)[ \\t]+(?:final[ \\t]+)?"
                    + "(?!class\\b)(?:static[ \\t]+)?[\\w<>\\[\\],.\\s?]*?"
                    + "(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]+)?\\{",
            Pattern.MULTILINE);

    /** 明显的纯包装方法，不参与审计（降低误报）。 */
    private static final Set<String> IGNORED_METHOD_NAMES = Set.of(
            "toString", "equals", "hashCode", "getClass");

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

    private List<Violation> auditFile(SourceFile file) {
        String content = file.content();
        String className = extractClassName(content);
        List<Violation> out = new ArrayList<>();

        Matcher m = METHOD_SIGNATURE.matcher(content);
        while (m.find()) {
            String methodName = m.group(1);
            String params = m.group(2);
            if (IGNORED_METHOD_NAMES.contains(methodName)) {
                continue;
            }
            if (isGetterOrSetter(methodName, params)) {
                continue;
            }
            String body = extractBody(content, m.end() - 1);
            if (body == null) {
                continue;
            }
            if (!touchesTenantData(body)) {
                continue;
            }
            if (hasOwnershipCheck(body)) {
                continue;
            }
            out.add(new Violation(file.path(), className, methodName,
                    lineOf(content, m.start()),
                    "引用了租户实体 " + referencedEntities(body)
                            + " 但方法内无归属校验（既未比对 tenantId，"
                            + "也未以 tenantId 作为查询条件）"));
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