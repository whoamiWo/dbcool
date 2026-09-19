package com.nocobase.im;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Slash 命令注册表的 Bean 定义。
 *
 * <p>{@link SlashCommandRegistry} 本身不标 {@code @Component}（它只是 SPI 容器，
 * 命令由 {@link SlashCommandRegistry#defaultRegistry()} 初始化），
 * 因此在此显式暴露为 Bean，供 {@link ImSlashController} 注入。
 */
@Configuration
public class SlashCommandConfig {

    @Bean
    public SlashCommandRegistry slashCommandRegistry() {
        return SlashCommandRegistry.defaultRegistry();
    }
}
