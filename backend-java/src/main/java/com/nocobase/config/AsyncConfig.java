package com.nocobase.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 @Async(Week 7 异步迁移用)与 @Scheduled(Week 41 复核 D4a 定时触发)。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
