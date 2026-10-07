package com.nocobase.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.im.ChannelService;
import com.nocobase.im.PresenceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 审计留痕覆盖度扫描器的反向验证测试（T4）。
 *
 * <p><b>目的</b>：确保扫描器不会"假阳性"或"假阴性"。
 *
 * <p><b>方法</b>：
 * <ol>
 *   <li>创建一个临时的 Controller 类，包含一个 {@code @PostMapping} 但无埋点的方法</li>
 *   <li>运行扫描器，必须报出该方法</li>
 *   <li>删除探针后，重新运行，必须干净通过</li>
 * </ol>
 */
class AuditTrailCoverageScannerReverseTest {

    private Path tempDir;
    private Path controllerFile;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("audit-scanner-test");
        controllerFile = tempDir.resolve("ProbeController.java");
    }

    @Test
    void scan_detectsMissingAudit() throws IOException {
        String probeContent = """
                package com.example;
                
                import org.springframework.web.bind.annotation.*;
                
                @RestController
                @RequestMapping("/api/probe")
                public class ProbeController {
                    
                    @PostMapping("/create")
                    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
                        return Map.of("code", 0, "message", "success");
                    }
                }
                """;
        Files.writeString(controllerFile, probeContent);

        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(tempDir);
        var missing = scanner.scan();

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("create");
    }

    @Test
    void scan_passesWithAudit() throws IOException {
        String probeContent = """
                package com.example;
                
                import com.nocobase.audit.AuditService;
                import org.springframework.web.bind.annotation.*;
                import java.util.Map;
                
                @RestController
                @RequestMapping("/api/probe")
                public class ProbeController {
                    
                    private final AuditService auditService;
                    
                    public ProbeController(AuditService auditService) {
                        this.auditService = auditService;
                    }
                    
                    @PostMapping("/create")
                    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
                        auditService.log("tenant_default", "user1", "alice", "probe.create", "probe", "1", Map.of());
                        return Map.of("code", 0, "message", "success");
                    }
                }
                """;
        Files.writeString(controllerFile, probeContent);

        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(tempDir);
        var missing = scanner.scan();

        assertThat(missing).isEmpty();
    }

    @Test
    void scan_detectsMultipleMappings() throws IOException {
        String probeContent = """
                package com.example;
                
                import org.springframework.web.bind.annotation.*;
                import java.util.Map;
                
                @RestController
                @RequestMapping("/api/probe")
                public class ProbeController {
                    
                    @PostMapping("/create")
                    public Map<String, Object> create() {
                        return Map.of();
                    }
                    
                    @PutMapping("/update")
                    public Map<String, Object> update() {
                        return Map.of();
                    }
                    
                    @DeleteMapping("/delete")
                    public Map<String, Object> delete() {
                        return Map.of();
                    }
                }
                """;
        Files.writeString(controllerFile, probeContent);

        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(tempDir);
        var missing = scanner.scan();

        assertThat(missing).hasSize(3);
    }

    @Test
    void writeBaseline_createsFile() throws IOException {
        String probeContent = """
                package com.example;
                
                import org.springframework.web.bind.annotation.*;
                import java.util.Map;
                
                @RestController
                @RequestMapping("/api/probe")
                public class ProbeController {
                    
                    @PostMapping("/create")
                    public Map<String, Object> create() {
                        return Map.of();
                    }
                }
                """;
        Files.writeString(controllerFile, probeContent);

        AuditTrailCoverageScanner scanner = new AuditTrailCoverageScanner(tempDir);
        var missing = scanner.scan();

        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Path baseline = docsDir.resolve("audit-trail-baseline.txt");

        java.io.PrintWriter writer = new java.io.PrintWriter(Files.newBufferedWriter(baseline));
        writer.println("# Audit Trail Coverage Baseline");
        writer.println("# Generated: " + java.time.Instant.now());
        writer.println("#");
        writer.println("# 以下方法在 Controller 中有写操作，但未调用 auditService.log():");
        writer.println("# 格式: file:行号 -> 方法名");
        writer.println("#");
        writer.println();
        for (String entry : missing) {
            writer.println(entry);
        }
        writer.close();

        assertThat(Files.exists(baseline)).isTrue();
        String content = Files.readString(baseline);
        assertThat(content).contains("create");
    }
}