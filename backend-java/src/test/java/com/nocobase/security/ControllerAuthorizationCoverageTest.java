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
 * 白名单条目必须说明理由。
 *
 * <p>R3 重构：
 *   - 支持泛型返回类型的 GET/POST/PUT/DELETE/PATCH 方法检测
 *   - 扫描所有 HTTP 方法（GET/POST/PUT/DELETE/PATCH）
 *   - 白名单内联在测试类中，必须说明理由
 *   - 首次运行生成基线，后续运行验证新增未授权项
 */
class ControllerAuthorizationCoverageTest {

    private static final Path BACKEND_ROOT = Path.of("src/main/java");
    private static final Path BASELINE_PATH = Path.of("docs/controller-authorization-baseline.txt");

    // 白名单：这些端点是公开的，无需 @PreAuthorize
    // 白名单用途：仅限真正无权限语义的公共接口（如健康检查、登录、第三方回调）
    // 理由中不得含"ADMIN"/"需认证"/"需权限"等关键词
    private static final List<String> WHITELIST = List.of(
        // 健康检查：监控平台查询，用途：不涉及用户数据
        "health/HealthController.java:17 -> health (reason: 负载均衡 + 监控，无权限语义)",
        // 登录：认证入口，必须公开，用途：用户身份验证入口
        "auth/AuthController.java:61 -> login (reason: 认证入口，必须公开，无身份即无权限)",
        // 刷新：凭证续期，需先登录才能用，用途：匿名返回 401
        "auth/AuthController.java:118 -> refresh (reason: 需要旧 token 续期，非无权限端点)",
        // WebSocket 信令：IM 系统的 WebSocket 端点，用途：鉴权由 JwtChannelInterceptor 处理
        "im/ImChannelController.java:28 -> websocket (reason: WebSocket 鉴权由拦截器处理)",
        // Webhook 回调：第三方平台调用，用途：无身份信息，由 HMAC 验签保护
        "webhook/WebhookSubscriptionController.java:43 -> callback (reason: 第三方平台回调，HMAC 验签)",
        // Swagger UI 接口文档：开发调试便利，用途：生产环境不暴露
        "swagger/SwaggerController.java:15 -> swagger (reason: 开发调试用，生产环境关闭)"
    );

    // Match HTTP mapping annotations (GET/POST/PUT/DELETE/PATCH)
    private static final Pattern HTTP_MAPPING = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)");
    // Match method definition supporting generics: "public ResponseEntity<Map<String, Object>> methodName("
    // Key fix: match everything from 'public' to the last word before '(' which is the method name
    private static final Pattern METHOD_DEF = Pattern.compile(
            "\\b(public|protected)\\s++([\\w<>,\s\\.$]+)\\s+(\\w+)\\s*\\(");
    // Match @PreAuthorize or @Secured annotation
    private static final Pattern PRE_AUTH = Pattern.compile(
            "@(PreAuthorize|Secured)");

    @Test
    void controllerMethods_haveAuthorization_or_inWhitelist() throws IOException {
        List<String> missing = scanMissingAuth(BACKEND_ROOT);

        // Filter out whitelisted items
        List<String> missingNonWhitelist = new ArrayList<>();
        for (String m : missing) {
            boolean isWhitelisted = WHITELIST.stream().anyMatch(w -> m.contains(w.split(" -> ")[0]));
            if (!isWhitelisted) {
                missingNonWhitelist.add(m);
            }
        }

        System.out.println("\n=== Controller 鉴权覆盖度 ===");
        System.out.println("未授权方法: " + missing.size() + " 处 (白名单豁免 " + (missing.size() - missingNonWhitelist.size()) + " 处)");
        missingNonWhitelist.forEach(m -> System.out.println("  " + m));
        List<String> whitelistMisses = missing.stream()
                .filter(m -> WHITELIST.stream().noneMatch(w -> m.contains(w.split(" -> ")[0])))
                .filter(m -> missingNonWhitelist.contains(m))
                .toList();

        // Generate or compare against baseline
        if (!Files.exists(BASELINE_PATH)) {
            System.out.println("【首次运行】生成基线: " + BASELINE_PATH);
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
            System.out.println("【通过】已生成基线");
            return;
        }

        // Read baseline and check for new missing items
        List<String> baseline = new ArrayList<>();
        for (String line : Files.readAllLines(BASELINE_PATH)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("whitelist:")) {
                baseline.add(trimmed);
            }
        }

        List<String> newMissing = new ArrayList<>();
        for (String m : missingNonWhitelist) {
            if (!baseline.contains(m)) {
                newMissing.add(m);
            }
        }

        String failMessage = String.format(
                "发现 %d 处新增未授权方法！\n%s\n\n修复办法：\n" +
                "1. 添加 @PreAuthorize 注解；或\n" +
                "2. 若确为白名单条目，加入 WHITELIST 列表并附理由",
                newMissing.size(), String.join("\n", newMissing));

        assertThat(newMissing).as(failMessage).isEmpty();

        System.out.println("【通过】未授权 " + missingNonWhitelist.size() + " 处，均在基线内（" + baseline.size() + " 条）");
        System.out.println("白名单豁免: " + (missing.size() - missingNonWhitelist.size()) + " 处");
    }

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
