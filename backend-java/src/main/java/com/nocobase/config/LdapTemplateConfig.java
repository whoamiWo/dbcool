package com.nocobase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * LdapTemplate Bean 配置 — 仅在显式配置 LDAP 地址时创建（`nocobase.ldap.url`）。
 *
 * <p>此前未配置真实 LDAP 时也注册指向 `ldap://localhost:389` 的占位 Bean，导致
 * Spring Boot 自动装配的 LdapHealthIndicator 把占位地址当成真实 LDAP 巡检，
 * 无 LDAP 的部署 /actuator/health 恒为 DOWN（每次访问打 Connection refused 堆栈），
 * 容器健康检查不得不绕行自建 /api/health/ready。
 *
 * <p>修复：默认不再注册占位 Bean；LDAP 功能（LdapSyncService）需在部署时显式
 * 配置 `nocobase.ldap.url` 才会注入 LdapTemplate。未配置时不创建 Bean，
 * 健康检查不再受占位地址影响（禁止假成功的红线不变：配置了错误地址时同步仍会
 * 抛出明确异常）。使用方 LdapSyncService 对 LdapTemplate 为可选注入，
 * 未配置时同步接口返回明确错误。
 */
@Configuration
@ConditionalOnClass(LdapTemplate.class)
@ConditionalOnProperty(prefix = "nocobase.ldap", name = "url")
public class LdapTemplateConfig {

    @Bean
    @ConditionalOnMissingBean(LdapContextSource.class)
    public LdapContextSource ldapContextSource(
            @Value("${nocobase.ldap.url}") String url,
            @Value("${nocobase.ldap.base:}") String base,
            @Value("${nocobase.ldap.username:}") String username,
            @Value("${nocobase.ldap.password:}") String password) {
        LdapContextSource source = new LdapContextSource();
        source.setUrls(url.split(","));
        source.setBase(base);
        if (!username.isEmpty()) {
            source.setUserDn(username);
            source.setPassword(password);
        }
        source.setPooled(false);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(LdapTemplate.class)
    public LdapTemplate ldapTemplate(LdapContextSource contextSource) {
        return new LdapTemplate(contextSource);
    }
}
