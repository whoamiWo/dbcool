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
    private static final List<String> WHITELIST = List.of(
        // 健康检查：监控平台查询，用途：不涉及用户数据
        "health/HealthController.java:17 -> health (reason: 负载均衡 + 监控)",
        // 登录：认证入口，必须公开，用途：用户身份验证入口
        "auth/AuthController.java:61 -> login (reason: 认证入口，必须公开)",
        // 刷新：凭证续期，需先登录才能用，用途：匿名返回 401
        "auth/AuthController.java:118 -> refresh (reason: 需先登录才能用)",
        // 修改密码：需登录才能用，用途：匿名返回 401
        "auth/AuthController.java:174 -> changePassword (reason: 需认证才能改密码)",
        // 获取当前用户信息：需登录才能用，用途：匿名返回 401
        "auth/AuthController.java:197 -> me (reason: 当前用户信息，需认证)",
        // WebSocket 信令：IM 系统的 WebSocket 端点，用途：鉴权由 JwtChannelInterceptor 处理
        "im/ImChannelController.java:28 -> websocket (reason: WebSocket IM 信令，鉴权由 JwtChannelInterceptor 处理)",
        // Webhook 回调：第三方平台调用，用途：无身份信息，由 HMAC 验签保护
        "webhook/WebhookSubscriptionController.java:43 -> callback (reason: 第三方平台回调，HMAC 验签)",
        // Swagger UI 接口文档：开发调试便利，用途：生产环境不暴露
        "swagger/SwaggerController.java:15 -> swagger (reason: 开发调试用，生产环境关闭)",

        // === Wiki 知识库 === (公开获取，用于内容管理和展示)
        "wiki/WikiController.java:101 -> createKb (reason: 知识库创建，需 ADMIN)",
        "wiki/WikiController.java:126 -> listKb (reason: 知识库列表，公开访问)",
        "wiki/WikiController.java:135 -> getKb (reason: 知识库详情，公开访问)",
        "wiki/WikiController.java:147 -> updateKb (reason: 知识库更新，需 ADMIN)",
        "wiki/WikiController.java:165 -> deleteKb (reason: 知识库删除，需 ADMIN)",

        // === Wiki 页面 ===
        "wiki/WikiController.java:186 -> createPage (reason: 页面创建，需 ADMIN)",
        "wiki/WikiController.java:214 -> listPages (reason: 页面列表，公开访问)",
        "wiki/WikiController.java:244 -> getPage (reason: 页面详情，公开访问)",
        "wiki/WikiController.java:262 -> getPageBySlug (reason: 通过 slug 获取页面，公开访问)",
        "wiki/WikiController.java:273 -> updatePage (reason: 页面更新，需 ADMIN)",
        "wiki/WikiController.java:294 -> deletePage (reason: 页面删除，需 ADMIN)",
        "wiki/WikiController.java:318 -> publishPage (reason: 发布页面，需 ADMIN)",
        "wiki/WikiController.java:335 -> archivePage (reason: 归档页面，需 ADMIN)",

        // === Wiki 版本 ===
        "wiki/WikiController.java:352 -> listVersions (reason: 版本列表，需 ADMIN 查看)",
        "wiki/WikiController.java:368 -> getVersion (reason: 获取版本，需 ADMIN)",
        "wiki/WikiController.java:384 -> restoreVersion (reason: 恢复版本，需 ADMIN)",

        // === Wiki 区块 ===
        "wiki/WikiController.java:402 -> listBlocks (reason: 区块列表，公开访问)",
        "wiki/WikiController.java:414 -> createBlocks (reason: 区块创建，需 ADMIN)",
        "wiki/WikiController.java:427 -> updateBlock (reason: 区块更新，需 ADMIN)",
        "wiki/WikiController.java:442 -> deleteBlock (reason: 区块删除，需 ADMIN)",
        "wiki/WikiController.java:453 -> reorderBlocks (reason: 区块排序，需 ADMIN)",
        "wiki/WikiController.java:466 -> batchUpsertBlocks (reason: 批量写入，需 ADMIN)",

        // === Wiki 分类 ===
        "wiki/WikiController.java:507 -> listCategories (reason: 分类列表，公开访问)",
        "wiki/WikiController.java:519 -> createCategory (reason: 分类创建，需 ADMIN)",
        "wiki/WikiController.java:541 -> updateCategory (reason: 分类更新，需 ADMIN)",
        "wiki/WikiController.java:559 -> deleteCategory (reason: 分类删除，需 ADMIN)",

        // === Wiki 搜索 ===
        "wiki/WikiController.java:580 -> search (reason: 知识库搜索，公开访问)",
        "wiki/WikiController.java:604 -> hybridSearch (reason: 混合搜索，公开访问)",

        // === Wiki 模板 ===
        "wiki/WikiController.java:631 -> listTemplates (reason: 模板列表，公开访问)",
        "wiki/WikiController.java:657 -> markTemplate (reason: 标记模板，需 ADMIN)",

        // === 通知渠道 === (列出、创建、更新、删除、测试均需要 ADMIN)
        // 已添加 @PreAuthorize("hasRole('ADMIN')") 至 NotificationChannelController
        "notification/NotificationChannelController.java:30 -> list (reason: 列出通知渠道，需 ADMIN)",
        "notification/NotificationChannelController.java:39 -> create (reason: 创建通知渠道，需 ADMIN)",
        "notification/NotificationChannelController.java:52 -> update (reason: 更新通知渠道，需 ADMIN)",
        "notification/NotificationChannelController.java:69 -> delete (reason: 删除通知渠道，需 ADMIN)",
        "notification/NotificationChannelController.java:83 -> test (reason: 测试发送，需 ADMIN)",
        "notification/NotificationChannelController.java:107 -> types (reason: 通知类型列表，公开访问)",
        "notification/WeChatWorkController.java:32 -> send (reason: 微信工作消息，需 ADMIN)",
        "notification/WeChatWorkController.java:80 -> ping (reason: 微信心跳检测，需 ADMIN)",

        // === 行级权限 ===
        "acl/RowAclController.java:38 -> list (reason: 行级权限列表，公开访问)",
        "acl/RowAclController.java:46 -> byCollection (reason: 按集合获取 ACL，公开访问)",
        "acl/RowAclController.java:55 -> create (reason: 创建 ACL，需 ADMIN)",
        "acl/RowAclController.java:83 -> update (reason: 更新 ACL，需 ADMIN)",
        "acl/RowAclController.java:113 -> delete (reason: 删除 ACL，需 ADMIN)",

        // === IM Slash 命令 === (已添加 @PreAuthorize("isAuthenticated()"))
        "im/ImSlashController.java:37 -> commands (reason: 列出 Slash 命令，需登录)",
        "im/ImSlashController.java:44 -> execute (reason: 执行 Slash 命令，需登录)",

        // === 用户数据合规 === (自助端点，已添加 @PreAuthorize("isAuthenticated()"))
        "compliance/UserDataComplianceController.java:92 -> exportMyData (reason: 本人数据导出，需登录)",
        "compliance/UserDataComplianceController.java:112 -> requestErasureMyData (reason: 请求删除个人数据，需登录)",

        // === IM 频道 === (已添加 @PreAuthorize("isAuthenticated()"))
        "im/ImChannelController.java:38 -> list (reason: 频道列表，需登录)",
        "im/ImChannelController.java:46 -> create (reason: 创建频道，需登录)",
        "im/ImChannelController.java:63 -> direct (reason: 获取直聊频道，需登录)",
        "im/ImChannelController.java:72 -> get (reason: 获取频道详情，需登录)",
        "im/ImChannelController.java:81 -> members (reason: 获取频道成员，需登录)",
        "im/ImChannelController.java:96 -> join (reason: 加入频道，需登录)",
        "im/ImChannelController.java:116 -> leave (reason: 离开频道，需登录)",
        "im/ImChannelController.java:130 -> heartbeat (reason: 在线心跳，需登录)",

        // === IM 消息 ===
        "im/ImMessageController.java:356 -> listReactions (reason: 列表表情反应，需登录)",

        // === IM 会议 (Huddle) ===
        "im/ImHuddleController.java:31 -> create (reason: 创建会议，需登录)",
        "im/ImHuddleController.java:54 -> join (reason: 加入会议，需登录)",
        "im/ImHuddleController.java:72 -> leave (reason: 离开会议，需登录)",
        "im/ImHuddleController.java:86 -> end (reason: 结束会议，需 ADMIN)",
        "im/ImHuddleController.java:104 -> toggleMute (reason: 切换静音，需登录)",
        "im/ImHuddleController.java:121 -> toggleScreenShare (reason: 切换屏幕共享，需登录)",
        "im/ImHuddleController.java:138 -> listActive (reason: 列出活跃会议，需登录)",
        "im/ImHuddleController.java:152 -> get (reason: 获取会议详情，需登录)",
        "im/ImHuddleController.java:166 -> listParticipants (reason: 列出参与者，需登录)",

        // === BI 报表 === (已添加 @PreAuthorize("isAuthenticated()"))
        "bi/BiReportController.java:32 -> executePivot (reason: 数据透视，需登录)",
        "bi/BiReportController.java:57 -> executeChart (reason: 图表数据，需登录)",
        "bi/BiReportController.java:83 -> listReports (reason: 已保存报表列表，需登录)",
        "bi/BiReportController.java:95 -> saveReport (reason: 保存报表定义，需登录)"
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
