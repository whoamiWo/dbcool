package com.nocobase.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE74 / 🔒-8 集成测试 —— T2 接入门禁。
 *
 * <p><b>基线 + 禁止新增模式</b>：因存量违规过多（134 处），一次性修复会阻塞开发。
 * 本测试采用"基线 + 禁止新增"策略：
 * <ol>
 *   <li>首次运行时生成基线文件 {@code docs/tenant-isolation-baseline.txt}</li>
 *   <li>后续运行：若违规数 > 基线数，则失败（新增违规）</li>
 *   <li>若违规数 ≤ 基线数，则通过（允许逐步修复存量）</li>
 * </ol>
 *
 * <p><b>R3 加固</b>：基线从"单一数字"改为"违规清单（类名#方法名）"，按集合比对。
 * 新增即失败，数量仅作辅助打印。防止"改大数字就能让新违规合法化"。
 */
class TenantIsolationIntegrationTest {

    private static final Path BASELINE_FILE =
            Path.of("docs/tenant-isolation-baseline.txt");

    /** 将 Violation 转换为唯一标识（类名#方法名） */
    private static String violationKey(TenantIsolationAuditor.Violation v) {
        return v.className() + "#" + v.methodName();
    }

    @Test
    void 生产代码无新增越权归属缺失() throws IOException {
        Path root = SourceScanner.locateBackendRoot();
        List<Path> sources = SourceScanner.javaSources(root);

        Set<String> tenantEntities = SourceScanner.tenantScopedEntityNames(sources);
        List<TenantIsolationAuditor.SourceFile> units = SourceScanner.auditedUnits(sources);

        TenantIsolationAuditor auditor = new TenantIsolationAuditor(tenantEntities);
        List<TenantIsolationAuditor.Violation> violations = auditor.audit(units);

        // 打印原始输出（§7 回报清单要求）
        System.out.println("\n=== 多租户归属校验审计报告 ===");
        System.out.println("扫描范围：" + units.size() + " 个 Controller/Service 文件");
        System.out.println("租户实体：" + tenantEntities.size() + " 个");
        System.out.println("违规数量：" + violations.size());
        if (!violations.isEmpty()) {
            System.out.println("\n违规清单（前 20 处）：");
            violations.stream().limit(20).forEach(v -> System.out.println("  " + v));
            if (violations.size() > 20) {
                System.out.println("  ... 还有 " + (violations.size() - 20) + " 处");
            }
        }
        System.out.println("==============================\n");

        // 将当前违规转为唯一标识集合
        Set<String> currentViolations = violations.stream()
                .map(TenantIsolationIntegrationTest::violationKey)
                .collect(Collectors.toSet());

        // 基线管理：首次生成，后续对比
        Set<String> baselineViolations;
        if (Files.exists(BASELINE_FILE)) {
            String content = Files.readString(BASELINE_FILE, StandardCharsets.UTF_8);
            baselineViolations = content.lines()
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.toSet());
        } else {
            // 首次运行：写入基线（违规清单）
            String baselineContent = currentViolations.stream()
                    .sorted()
                    .collect(Collectors.joining("\n"));
            Files.writeString(BASELINE_FILE, baselineContent);
            baselineViolations = currentViolations;
            System.out.println("【首次运行】已生成基线文件，违规数=" + violations.size());
            return;
        }

        // R3：按集合比对，新增即失败
        Set<String> newViolations = currentViolations.stream()
                .filter(k -> !baselineViolations.contains(k))
                .collect(Collectors.toSet());

        if (!newViolations.isEmpty()) {
            fail("发现 " + newViolations.size() + " 处新增租户归属校验缺失！\n"
                    + "新增违规清单：\n" + newViolations.stream()
                    .sorted()
                    .collect(Collectors.joining("\n")) + "\n\n"
                    + "当前违规数 " + violations.size() + "，基线数 " + baselineViolations.size() + "\n"
                    + "请修复后再提交。详见 docs/tenant-isolation-audit.md");
        }

        System.out.println("【通过】当前违规数 " + violations.size() + " ≤ 基线 " + baselineViolations.size()
                + "，无新增违规。");
    }
}
