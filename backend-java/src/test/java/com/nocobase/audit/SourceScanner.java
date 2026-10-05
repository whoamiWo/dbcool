package com.nocobase.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从 {@code src/main/java} 读取生产源码，供 {@link TenantIsolationAuditor} 审计。
 *
 * <p>走文件系统而非 Spring 容器：审计必须能在没有数据库/Redis 的环境下作为
 * 纯 {@code mvn test} 门禁执行（否则审计器本身会因环境依赖而挂掉）。
 */
public final class SourceScanner {

    private static final Pattern TENANT_FIELD =
            Pattern.compile("private\\s+String\\s+tenantId\\s*;");
    private static final Pattern CLASS_DECL =
            Pattern.compile("(?:public\\s+)?(?:class|interface|enum)\\s+(\\w+)");

    private SourceScanner() {}

    /** 列出 {@code src/main/java} 下全部 .java 文件。 */
    public static List<Path> javaSources(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描源码失败: " + root, e);
        }
    }

    /**
     * 找出所有"带 tenantId 字段"的实体类简单名。
     *
     * <p>这是审计规则的输入之一：方法只要引用了这些实体，就说明它在动租户数据。
     */
    public static Set<String> tenantScopedEntityNames(List<Path> sources) {
        Set<String> names = new LinkedHashSet<>();
        for (Path p : sources) {
            String fileName = p.getFileName().toString();
            if (!fileName.endsWith("Entity.java")) {
                continue;
            }
            String content = read(p);
            if (TENANT_FIELD.matcher(content).find()) {
                Matcher m = CLASS_DECL.matcher(content);
                if (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    /** 读取所有 Controller / Service 源码为可审计单元。 */
    public static List<TenantIsolationAuditor.SourceFile> auditedUnits(List<Path> sources) {
        List<TenantIsolationAuditor.SourceFile> out = new ArrayList<>();
        for (Path p : sources) {
            String fileName = p.getFileName().toString();
            if (fileName.endsWith("Controller.java") || fileName.endsWith("Service.java")) {
                out.add(new TenantIsolationAuditor.SourceFile(p.toString(), read(p)));
            }
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取源码失败: " + p, e);
        }
    }

    /** 定位项目根：优先用系统属性（surefire 可传入），否则按当前目录向上找 pom.xml。 */
    public static Path locateBackendRoot() {
        String configured = System.getProperty("nocobase.backend.root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("src/main/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("无法定位 backend-java 根目录（未找到 pom.xml + src/main/java）");
    }
}