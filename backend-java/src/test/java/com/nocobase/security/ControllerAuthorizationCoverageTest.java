package com.nocobase.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * PHASE95 T3 / R3: Controller 鉴权覆盖度架构测试.
 *
 * <p>策略：扫描所有 *Controller.java，检查写方法（POST/PUT/DELETE/PATCH）
 * 和读方法（GET）是否带有 @PreAuthorize 或 @Secured 注解，或出现在白名单中。
 *
 * <p>白名单用途：仅限无权限语义的公共接口（如健康检查、对外 webhook 回调）。
 * 白名单条目必须说明理由，且不得用"ADMIN/需认证"等措辞掩盖真实鉴权需求。
 *
 * <p>R3 重构：
 *   - 支持泛型返回类型的 GET/POST/PUT/DELETE/PATCH 方法检测
 *   - 扫描所有 HTTP 方法（GET/POST/PUT/DELETE/PATCH）
 *   - 白名单内联在测试类中，必须说明理由
 *
 * <p>PHASE114 审计修正 —— 本测试此前存在「假绿」：
 *   原逻辑只比对「新增未授权项」（见历史 :116-129），存量缺口一经写入基线即被
 *   永久豁免。实测输出「未授权 215 处，均在基线内」却 BUILD SUCCESS，
 *   导致文档声称的「鉴权覆盖」实为「冻结基线」，与真实安全状态不符。
 *
 * <p>本次改造（让门禁说实话）：
 *   1. 棘轮（ratchet）：docs/authorization-ratchet.txt 限定允许的最大缺口数，
 *      当前缺口 > 阈值即失败；修复后必须下调阈值 → 进度只进不退。
 *   2. 白名单强校验：每条必须带非空 reason，且不得含 ADMIN/需认证/需权限等
 *      掩盖性措辞（防止用白名单偷渡本该鉴权的端点）。
 *   3. 失效白名单检测：白名单引用的源文件不存在即失败（此前
 *      swagger/SwaggerController.java:15 指向全仓不存在的文件却一直被豁免）。
 *   4. 基线重写开关：-Dauthz.updateBaseline=true 时重新生成基线与阈值。
 *      注意：该开关仅用于「修复后同步行号」，不得用于抬高阈值掩盖缺口。
 */
class ControllerAuthorizationCoverageTest {

    private static final Path BACKEND_ROOT = Path.of("src/main/java");
    private static final Path BASELINE_PATH = Path.of("docs/controller-authorization-baseline.txt");
    private static final Path RATCHET_PATH = Path.of("docs/authorization-ratchet.txt");

    /** 基线重写开关：仅在修复端点后同步行号时使用。 */
    private static final boolean UPDATE_BASELINE =
            Boolean.parseBoolean(System.getProperty("authz.updateBaseline", "false"));

    // 白名单：这些端点是公开的，无需 @PreAuthorize
    // 白名单用途：仅限真正无权限语义的公共接口（如健康检查、登录、第三方回调）
    // 理由中不得含"ADMIN"/"需认证"/"需权限"等关键词（否则等于用白名单掩盖鉴权需求）
    private static final List<String> WHITELIST = List.of(
        // 健康检查：监控平台查询，用途：不涉及用户数据
        "health/HealthController.java:17 -> health (reason: 负载均衡 + 监控，无权限语义)",
        // 登录：认证入口，必须公开，用途：用户身份验证入口
        // PHASE96 T6: 行号 61→66（本批在 login 前插入了 LDAP 占位口令防线）
        "auth/AuthController.java:66 -> login (reason: 认证入口，必须公开，无身份即无权限)",
        // 刷新：凭证续期，需先登录才能用，用途：匿名返回 401
        // PHASE96 T6: 行号 118→134（同上，防线代码导致的下移）
        "auth/AuthController.java:134 -> refresh (reason: 需要旧 token 续期，非无权限端点)",
        // WebSocket 信令：IM 系统的 WebSocket 端点，用途：鉴权由 JwtChannelInterceptor 处理
        "im/ImChannelController.java:28 -> websocket (reason: WebSocket 鉴权由拦截器处理)",
        // Webhook 回调：第三方平台调用，用途：无身份信息，由 HMAC 验签保护
        "webhook/WebhookSubscriptionController.java:43 -> callback (reason: 第三方平台回调，HMAC 验签)",
        // PHASE114: 第三方回调（SecurityConfig permitAll + Controller 内 HMAC 验签）。
        // 这些端点若误加 @PreAuthorize，回调方无 JWT 将直接 403 → 功能中断，故必须白名单。
        "integration/slack/SlackController.java:77 -> eventsCallback (reason: 第三方平台回调，SecurityConfig permitAll + HMAC 验签)",
        "integration/feishu/FeishuController.java:68 -> eventsCallback (reason: 第三方平台回调，SecurityConfig permitAll + HMAC 验签)",
        "integration/wecom/WeComController.java:78 -> callback (reason: 第三方平台回调，SecurityConfig permitAll + HMAC 验签)",
        "integration/mattermost/MattermostController.java:42 -> incomingWebhook (reason: 第三方平台回调，SecurityConfig permitAll + HMAC 验签)",
        "integration/dingtalk/DingTalkController.java:243 -> approvalCallback (reason: 钉钉审批回调，SecurityConfig permitAll + HMAC 验签)",
        "integration/dingtalk/DingTalkController.java:367 -> eventCallback (reason: 钉钉事件回调，SecurityConfig permitAll + HMAC 验签)",
        // 钉钉 OAuth 入口：免密登录入口，必须公开（SecurityConfig permitAll）
        "integration/dingtalk/DingTalkController.java:85 -> authUrlGet (reason: 钉钉 OAuth 授权入口，必须公开)",
        "integration/dingtalk/DingTalkController.java:93 -> authUrlPost (reason: 钉钉 OAuth 授权入口，必须公开)",
        "integration/dingtalk/DingTalkController.java:112 -> login (reason: 钉钉免密登录入口，必须公开)",
        // 健康检查探针 /api/health/** 在 SecurityConfig permitAll。
        // 若补 @PreAuthorize 会让负载均衡与容器 healthcheck 拿不到 200 → 服务被摘除，故必须白名单。
        "health/HealthController.java:52 -> ready (reason: 健康检查探针，SecurityConfig permitAll + 负载均衡/容器探活)",
        "health/HealthController.java:82 -> poolStats (reason: 健康检查与连接池监控端点，SecurityConfig permitAll + 运维监控)"
    );

    /** 白名单理由中出现这些词，说明该端点其实需要鉴权，是偷渡。 */
    private static final List<String> FORBIDDEN_REASON_WORDS =
            List.of("ADMIN", "需认证", "需权限", "需要权限", "TODO", "待补");

    // Match HTTP mapping annotations (GET/POST/PUT/DELETE/PATCH)
    private static final Pattern HTTP_MAPPING = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)");
    // Match method definition supporting generics
    private static final Pattern METHOD_DEF = Pattern.compile(
            "\\b(public|protected)\\s++([\\w<>,\\s\\.$]+)\\s+(\\w+)\\s*\\(");
    // Match @PreAuthorize or @Secured annotation
    private static final Pattern PRE_AUTH = Pattern.compile(
            "@(PreAuthorize|Secured)");

    @Test
    void controllerMethods_haveAuthorization_or_inWhitelist() throws IOException {
        // ---- 0. 白名单自身合规性（先校验，防止用白名单掩盖真实缺口）----
        List<String> whitelistProblems = validateWhitelist();
        assertThat(whitelistProblems)
                .as("白名单条目不合规：\n" + String.join("\n", whitelistProblems))
                .isEmpty();

        List<String> missing = scanMissingAuth(BACKEND_ROOT);

        // Filter out whitelisted items
        List<String> missingNonWhitelist = new ArrayList<>();
        for (String m : missing) {
            if (!isWhitelisted(m)) {
                missingNonWhitelist.add(m);
            }
        }

        System.out.println("\n=== Controller 鉴权覆盖度 ===");
        System.out.println("未授权方法: " + missing.size()
                + " 处 (白名单豁免 " + (missing.size() - missingNonWhitelist.size()) + " 处)");
        missingNonWhitelist.forEach(m -> System.out.println("  " + m));

        // ---- 1. 基线重写开关（仅修复后同步行号用）----
        if (UPDATE_BASELINE) {
            writeBaseline(missingNonWhitelist);
            writeRatchet(missingNonWhitelist.size());
            System.out.println("【基线已重写】缺口 " + missingNonWhitelist.size()
                    + " 处，阈值同步为 " + missingNonWhitelist.size()
                    + "（仅用于修复后同步；不得用于抬高阈值掩盖缺口）");
            return;
        }

        // ---- 2. 首次运行：生成基线与阈值 ----
        if (!Files.exists(BASELINE_PATH) || !Files.exists(RATCHET_PATH)) {
            writeBaseline(missingNonWhitelist);
            writeRatchet(missingNonWhitelist.size());
            System.out.println("【首次运行】生成基线 " + BASELINE_PATH
                    + " 与阈值 " + RATCHET_PATH + "（缺口 " + missingNonWhitelist.size() + " 处）");
            return;
        }

        // ---- 3. 棘轮校验：当前缺口不得多于阈值（这是本次审计的核心修复）----
        int ratchet = readRatchet();
        int current = missingNonWhitelist.size();

        assertThat(current)
                .as("鉴权缺口数回升！当前 " + current + " > 棘轮阈值 " + ratchet + "。\n"
                        + "缺口清单：\n" + String.join("\n", missingNonWhitelist) + "\n\n"
                        + "处置（按顺序）：\n"
                        + "1. 给端点补 @PreAuthorize（管理类 hasRole('ADMIN')，业务类 isAuthenticated()）；或\n"
                        + "2. 确为公开端点的，加入 WHITELIST 并写明理由（不得用 ADMIN/需认证 等措辞）；\n"
                        + "3. 修复后用 -Dauthz.updateBaseline=true 重写基线，并**下调** "
                        + RATCHET_PATH + " 至新的缺口数。\n"
                        + "禁止直接调高阈值——那等于回到 PHASE114 审计前的「冻结基线」假绿状态。")
                .isLessThanOrEqualTo(ratchet);

        // ---- 4. 若已修复使缺口下降，提示应下调阈值（锁定进度）----
        if (current < ratchet) {
            System.out.println("【提示】缺口已由 " + ratchet + " 降至 " + current
                    + "，请将 " + RATCHET_PATH + " 的阈值下调为 " + current + " 以锁定进度");
        }

        System.out.println("【通过】未授权 " + current + " 处，未超过棘轮阈值 " + ratchet);
        System.out.println("白名单豁免: " + (missing.size() - missingNonWhitelist.size()) + " 处");
    }

    // ============================================================
    //  白名单匹配 —— 按「文件路径 + 方法名」，不依赖行号
    // ============================================================
    /**
     * 判定某条缺口是否被白名单豁免。
     *
     * <p>PHASE114 修正：原实现按「文件:行号」子串匹配，行号一漂移（如 PHASE113 改
     * DingTalkController 插入代码）白名单即静默失效 —— 被豁免的回调端点重新计入缺口，
     * 后续若据此补 @PreAuthorize 会导致第三方回调 403（功能中断）。
     * 改为按「文件路径 + 方法名」匹配，行号仅作文档用途。
     */
    private static boolean isWhitelisted(String missingEntry) {
        if (!missingEntry.contains(" -> ")) {
            return false;
        }
        String[] mp = missingEntry.split(" -> ");
        String mRel = mp[0].substring(0, mp[0].lastIndexOf(':'));
        String mMethod = extractMethodName(mp[1]);

        for (String w : WHITELIST) {
            if (!w.contains(" -> ")) continue;
            String[] wp = w.split(" -> ");
            String wRel = wp[0].substring(0, wp[0].lastIndexOf(':'));
            String wMethod = extractMethodName(wp[1]);
            if (mRel.endsWith(wRel) && mMethod.equals(wMethod)) {
                return true;
            }
        }
        return false;
    }

    /** 从 "methodName (reason: ...)" 或 "methodName (missing @PreAuthorize)" 中提取方法名。 */
    private static String extractMethodName(String segment) {
        int paren = segment.indexOf(" (");
        return (paren >= 0 ? segment.substring(0, paren) : segment).trim();
    }

    // ============================================================
    //  白名单合规性校验
    // ============================================================
    private List<String> validateWhitelist() {
        List<String> problems = new ArrayList<>();
        for (String w : WHITELIST) {
            // 必须有 (reason: ...) 且内容非空
            int ri = w.indexOf("(reason:");
            if (ri < 0) {
                problems.add("缺 reason: " + w);
                continue;
            }
            String reason = w.substring(ri + "(reason:".length()).replace(")", "").trim();
            if (reason.isEmpty()) {
                problems.add("reason 为空: " + w);
            }
            for (String bad : FORBIDDEN_REASON_WORDS) {
                if (reason.contains(bad)) {
                    problems.add("reason 含掩盖性措辞 '" + bad + "'（该端点其实需要鉴权，不应入白名单）: " + w);
                }
            }
            // 引用的源文件必须存在（PHASE114: 曾存在指向不存在文件的失效条目）。
            // 白名单条目写的是部分路径（如 integration/slack/SlackController.java），
            // 匹配靠子串包含，故此处按「以该路径结尾」在全部 Controller 中查找。
            String filePart = w.split(" -> ")[0];
            String rel = filePart.substring(0, filePart.lastIndexOf(':'));
            boolean exists;
            try {
                exists = findControllers(BACKEND_ROOT).stream()
                        .anyMatch(p -> p.toString().replace('\\', '/').endsWith(rel));
            } catch (IOException e) {
                throw new IllegalStateException("扫描 Controller 失败", e);
            }
            if (!exists) {
                problems.add("白名单引用的源文件不存在（失效条目）: " + rel);
            }
        }
        return problems;
    }

    // ============================================================
    //  基线 / 阈值读写
    // ============================================================
    private void writeBaseline(List<String> missingNonWhitelist) throws IOException {
        Files.createDirectories(BASELINE_PATH.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Controller Authorization Baseline\n");
        sb.append("# Generated: ").append(java.time.Instant.now()).append("\n");
        sb.append("# 格式: 文件:行号 -> 方法名 (reason: 理由)\n\n");
        sb.append("# === 白名单 (WHITELIST) ===\n");
        for (String w : WHITELIST) {
            sb.append("# whitelist: ").append(w).append("\n");
        }
        sb.append("\n# === 未授权方法 ===\n");
        for (String m : missingNonWhitelist) {
            sb.append(m).append("\n");
        }
        Files.writeString(BASELINE_PATH, sb.toString());
    }

    private int readRatchet() throws IOException {
        for (String line : Files.readAllLines(RATCHET_PATH)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                try {
                    return Integer.parseInt(t);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        throw new IllegalStateException("棘轮文件 " + RATCHET_PATH + " 中未找到有效整数阈值");
    }

    private void writeRatchet(int value) throws IOException {
        Files.createDirectories(RATCHET_PATH.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Controller 方法级鉴权缺口 —— 棘轮阈值 (RATCHET)\n");
        sb.append("#\n");
        sb.append("# 语义：允许存在的「未授权端点」最大数量。只降不升。\n");
        sb.append("#   - 当前缺口数 > 本值 → 测试失败（防新增）\n");
        sb.append("#   - 修复一批端点后，必须同步下调本值（锁定进度，防回退）\n");
        sb.append("#\n");
        sb.append("# 修复时用 -Dauthz.updateBaseline=true 重新生成基线，\n");
        sb.append("# 然后把下面的数字改为「新的实际缺口数」，不得高于上一版。\n");
        sb.append("#\n");
        sb.append("# 历史（PHASE114 审计）：\n");
        sb.append("#   2026-10-10  首次生成基线  215  （此前为「冻结基线」，只比对新增 → 存量 215 处被永久豁免，门禁假绿）\n");
        sb.append("#   2026-10-19  引入棘轮机制，起始阈值 = 215\n");
        sb.append(value).append("\n");
        Files.writeString(RATCHET_PATH, sb.toString());
    }

    // ============================================================
    //  扫描
    // ============================================================
    List<String> scanMissingAuth(Path srcRoot) throws IOException {
        List<String> missing = new ArrayList<>();
        List<Path> controllers = findControllers(srcRoot);

        for (Path controller : controllers) {
            String content = Files.readString(controller);
            String[] lines = content.split("\n", -1);

            for (int i = 0; i < lines.length; i++) {
                if (HTTP_MAPPING.matcher(lines[i]).find()) {
                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                        Matcher mm = METHOD_DEF.matcher(lines[j]);
                        if (mm.find()) {
                            String methodName = mm.group(3);

                            // Check if @PreAuthorize or @Secured exists in nearby lines
                            boolean hasAuth = false;
                            for (int k = Math.max(0, i - 3); k <= j + 2 && k < lines.length; k++) {
                                if (PRE_AUTH.matcher(lines[k]).find()) {
                                    hasAuth = true;
                                    break;
                                }
                            }

                            if (!hasAuth) {
                                String rel = srcRoot.relativize(controller).toString().replace('\\', '/');
                                missing.add(rel + ":" + (i + 1) + " -> " + methodName + " (missing @PreAuthorize)");
                            }
                            break;
                        }
                    }
                }
            }
        }

        return missing;
    }

    private List<Path> findControllers(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walk(root)
                .filter(p -> p.toString().endsWith("Controller.java"))
                .forEach(result::add);
        return result;
    }
}
