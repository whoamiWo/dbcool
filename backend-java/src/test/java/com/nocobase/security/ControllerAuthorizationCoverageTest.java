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
 * PHASE95 T3: Controller 鉴权覆盖度架构测试.
 *
 * <p>策略：扫描所有 *Controller.java，检查 write 方法（POST/PUT/DELETE/PATCH）
 * 是否带有 @PreAuthorize 或 @Secured 注解，或出现在白名单中。
 *
 * <p>白名单用途：仅限无权限语义的公共接口（如健康检查、对外 webhook 回调）。
 * 白名单条目必须说明理由。
 */
class ControllerAuthorizationCoverageTest {

    private static final Path BACKEND_ROOT = Path.of("src/main/java");
    private static final Path BASELINE_PATH = Path.of("docs/controller-authorization-baseline.txt");

    private static final Pattern WRITE_MAPPING = Pattern.compile(
            "@(PostMapping|PutMapping|DeleteMapping|PatchMapping)");
    private static final Pattern METHOD_DEF = Pattern.compile(
            "\\b(public|protected)\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern PRE_AUTH = Pattern.compile(
            "@(PreAuthorize|Secured)");

    @Test
    void controllerWriteMethods_haveAuthorization_or_inWhitelist() throws IOException {
        List<String> missing = scanMissingAuth(BACKEND_ROOT);

        System.out.println("\n=== Controller 鉴权覆盖度 ===");
        System.out.println("未授权写操作: " + missing.size() + " 处");
        missing.forEach(m -> System.out.println("  " + m));

        if (!Files.exists(BASELINE_PATH)) {
            System.out.println("【首次运行】生成基线: " + BASELINE_PATH);
            Files.createDirectories(BASELINE_PATH.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Controller Authorization Baseline\n");
            sb.append("# Generated: ").append(java.time.Instant.now()).append("\n");
            sb.append("# 格式: 文件:行号 -> 方法名 (reason: 理由)\n\n");
            for (String m : missing) {
                sb.append(m).append("\n");
            }
            Files.writeString(BASELINE_PATH, sb.toString());
            System.out.println("【通过】已生成基线");
            return;
        }

        List<String> baseline = new ArrayList<>();
        for (String line : Files.readAllLines(BASELINE_PATH)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                baseline.add(line);
            }
        }

        List<String> newMissing = new ArrayList<>();
        for (String m : missing) {
            if (!baseline.contains(m)) {
                newMissing.add(m);
            }
        }

        // String message = String.format("发现 %d 处新增未授权写操作！\n%s\n\n修复办法：\n" +
        //                "1. 添加 @PreAuthorize 注解；或\n" +
        //                "2. 若确为白名单条目，加入基线 %s 并附理由\n",
        //        newMissing.size(), String.join("\n", newMissing), BASELINE_PATH);

        assertThat(newMissing).as("发现 %d 处新增未授权写操作！\n%s\n\n修复办法：\n" +
                "1. 添加 @PreAuthorize 注解；或\n" +
                "2. 若确为白名单条目，加入基线 %s 并附理由",
                newMissing.size(), String.join("\n", newMissing), BASELINE_PATH)
                .isEmpty();

        System.out.println("【通过】未授权 " + missing.size() + " 处，均在基线内（" + baseline.size() + " 条）");
    }

    List<String> scanMissingAuth(Path srcRoot) throws IOException {
        List<String> missing = new ArrayList<>();
        List<Path> controllers = findControllers(srcRoot);

        for (Path controller : controllers) {
            String content = Files.readString(controller);
            String[] lines = content.split("\n", -1);

            for (int i = 0; i < lines.length; i++) {
                if (WRITE_MAPPING.matcher(lines[i]).find()) {
                    for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                        Matcher mm = METHOD_DEF.matcher(lines[j]);
                        if (mm.find()) {
                            String methodName = mm.group(2);

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