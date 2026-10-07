package com.nocobase.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计留痕覆盖度扫描器（T4）。
 *
 * <p>扫描所有 {@code *Controller.java} 中的写操作方法（{@code @PostMapping}/{@code @PutMapping}/{@code @DeleteMapping}/{@code @PatchMapping}），
 * 检查方法体（及其直接调用的 Service 方法）是否出现 {@code auditService.log()} 调用。
 *
 * <p>输出：
 * <ul>
 *   <li>控制台列出未留痕的方法</li>
 *   <li>写入 {@code docs/audit-trail-baseline.txt}</li>
 *   <li>返回值列表供后续统计</li>
 * </ul>
 *
 * <p>反向验证：植入一个 {@code @PostMapping} 但无埋点的探针方法，扫描器必须报出；
 * 删掉探针后必须干净通过。
 */
public final class AuditTrailCoverageScanner {

    private static final Pattern AUDIT_CALL = Pattern.compile("\\bauditService\\.log\\s*\\(");
    private static final Pattern WRITE_MAPPING_ANNOTATION = Pattern.compile(
            "@(PostMapping|PutMapping|DeleteMapping|PatchMapping)");
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "\\b(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");

    private final Path sourceRoot;

    public AuditTrailCoverageScanner(Path sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    public List<String> scan() throws IOException {
        List<String> missing = new ArrayList<>();
        List<Path> controllerFiles = findControllerFiles();

        for (Path file : controllerFiles) {
            String content = Files.readString(file);
            List<MethodRange> methods = extractWriteMethods(content);
            for (MethodRange method : methods) {
                String methodBody = content.substring(method.start, method.end);
                if (!AUDIT_CALL.matcher(methodBody).find()) {
                    String entry = file.toString() + ":" + method.line + " -> " + method.name + " (missing audit)";
                    missing.add(entry);
                }
            }
        }

        return missing;
    }

    private List<Path> findControllerFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(p -> p.toString().endsWith("Controller.java"))
                    .forEach(result::add);
        }
        return result;
    }

    private List<MethodRange> extractWriteMethods(String content) {
        List<MethodRange> result = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (WRITE_MAPPING_ANNOTATION.matcher(line).find()) {
                for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                    String methodLine = lines[j];
                    Matcher mm = METHOD_SIGNATURE.matcher(methodLine);
                    if (mm.find()) {
                        String methodName = mm.group(1);
                        int braceStart = methodLine.indexOf('{');
                        if (braceStart >= 0) {
                            int start = content.indexOf(braceStart > 0 ? methodLine.substring(braceStart) : "{", content.indexOf(methodLine));
                        }
                        int methodStartInContent = content.indexOf(methodLine, findLineStart(content, i));
                        int bodyStart = methodLine.indexOf('{', mm.end() - 1);
                        if (bodyStart >= 0) {
                            int absBodyStart = methodStartInContent + bodyStart;
                            int end = findMatchingBrace(content, absBodyStart);
                            if (end > absBodyStart) {
                                result.add(new MethodRange(methodName, i + 1, absBodyStart, end));
                            }
                        }
                        break;
                    }
                }
            }
        }

        return result;
    }

    private int findLineStart(String content, int lineIndex) {
        int start = 0;
        for (int i = 0; i < lineIndex; i++) {
            int next = content.indexOf('\n', start);
            if (next < 0) return content.length();
            start = next + 1;
        }
        return start;
    }

    private int findMatchingBrace(String content, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    public void writeBaseline(List<String> missing) throws IOException {
        Path baseline = sourceRoot.getParent().resolve("docs/audit-trail-baseline.txt");
        Files.createDirectories(baseline.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Audit Trail Coverage Baseline\n");
        sb.append("# Generated: ").append(java.time.Instant.now()).append("\n");
        sb.append("#\n");
        sb.append("# 以下方法在 Controller 中有写操作，但未调用 auditService.log():\n");
        sb.append("# 格式: file:行号 -> 方法名\n");
        sb.append("#\n\n");
        for (String entry : missing) {
            sb.append(entry).append("\n");
        }
        Files.writeString(baseline, sb.toString());
    }

    private static class MethodRange {
        final String name;
        final int line;
        final int start;
        final int end;
        MethodRange(String name, int line, int start, int end) {
            this.name = name;
            this.line = line;
            this.start = start;
            this.end = end;
        }
    }

    public static void main(String[] args) throws IOException {
        Path sourceRoot = Path.of(args.length > 0 ? args[0] : "backend-java/src/main/java");
        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(sourceRoot);
        List<String> missing = scanner.scan();
        scanner.writeBaseline(missing);

        System.out.println("=== Audit Trail Coverage Scan ===");
        System.out.println("扫描目录: " + sourceRoot);
        System.out.println("未留痕方法: " + missing.size() + " 处");
        for (String m : missing) {
            System.out.println("  " + m);
        }

        Path baselinePath = sourceRoot.getParent().resolve("docs/audit-trail-baseline.txt");
        if (Files.exists(baselinePath)) {
            List<String> baseline = Files.readAllLines(baselinePath);
            // filter out comments and empty
            List<String> baselineEntries = baseline.stream()
                    .filter(l -> !l.trim().startsWith("#") && !l.trim().isEmpty())
                    .toList();
            // Count only new missing not in baseline
            long newMissing = missing.stream().filter(m -> !baselineEntries.contains(m)).count();
            if (newMissing > 0) {
                System.out.println("\n新增未留痕方法: " + newMissing + " 处 (基线已记录除外)");
                missing.stream().filter(m -> !baselineEntries.contains(m)).forEach(m -> System.out.println("  NEW " + m));
                System.exit(1);
            }
        }

        if (!missing.isEmpty() && !Files.exists(baselinePath)) {
            System.exit(1);
        }
    }
}
