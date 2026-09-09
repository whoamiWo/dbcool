package com.nocobase.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查端点.
 *
 * <p>Week 3 阶段 3 脚手架,Week 5+ 阶段 4 之后会被 Actuator 取代.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "nocobase-backend",
                "version", "0.0.1",
                "timestamp", Instant.now().toString()
        );
    }
}
