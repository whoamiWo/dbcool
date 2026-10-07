package com.nocobase.audit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * PHASE84：审计留痕覆盖度**正向门禁**（T4 的落地点）。
 *
 * <p><b>为什么需要这个测试</b>：PHASE83 写了扫描器，但"扫描真实生产代码 + 比对基线 +
 * 失败"的那段逻辑放在 {@code AuditTrailCoverageScanner.main()} 里 ——
 * surefire 只跑 {@code @Test}，**那段永远不会被执行**，等于门禁没接。
 * 而且它用 {@code System.exit(1)} 表达失败，一旦真在测试里被调用会直接杀掉 JVM。
 * 本类把它改造成真正的测试。
 *
 * <p><b>策略</b>：与 {@link TenantIsolationIntegrationTest} 一致 ——
 * "基线 + 禁止新增"。存量未留痕的写操作写入基线文件（允许后续逐步补），
 * 但**新增未留痕的写操作即失败**，防止"每次加接口都不记得埋点"。
 */
class AuditTrailCoverageIntegrationTest {

    @Test
    void 生产代码无新增未留痕写操作() throws IOException {
        Path backendRoot = SourceScanner.locateBackendRoot();
        Path srcJava = backendRoot.resolve("src/main/java");
        assertTrue(Files.isDirectory(srcJava), "源码目录不存在: " + srcJava);

        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(srcJava);
        List<String> missing = scanner.scan();

        System.out.println("\n=== 审计留痕覆盖度 ===");
        System.out.println("扫描范围: " + srcJava);
        System.out.println("未留痕写操作: " + missing.size() + " 处");

        Path baseline = scanner.baselinePath();
        if (!Files.exists(baseline)) {
            // 首次运行：生成基线
            scanner.writeBaseline(missing);
            System.out.println("【首次运行】已生成基线 " + baseline + "，未留痕 " + missing.size() + " 处");
            return;
        }

        Set<String> baselineEntries = Files.readAllLines(baseline).stream()
                .filter(l -> !l.trim().startsWith("#") && !l.trim().isEmpty())
                .collect(Collectors.toSet());

        List<String> newMissing = missing.stream()
                .filter(m -> !baselineEntries.contains(m))
                .toList();

        if (!newMissing.isEmpty()) {
            fail("发现 " + newMissing.size() + " 处新增未留痕写操作！\n"
                    + newMissing.stream().collect(Collectors.joining("\n"))
                    + "\n\n处理办法二选一：\n"
                    + "  1) 补 auditService.log(...) 埋点（推荐）；\n"
                    + "  2) 确属无需留痕的，把该条目加入基线 " + baseline + "\n"
                    + "严禁放宽扫描规则来消除条目（PHASE81 的教训）。\n"
                    + "当前未留痕 " + missing.size() + " 处，基线 " + baselineEntries.size() + " 条。");
        }

        System.out.println("【通过】未留痕 " + missing.size() + " 处，均在基线内（基线 "
                + baselineEntries.size() + " 条）");
        System.out.println("==============================\n");
    }
}
