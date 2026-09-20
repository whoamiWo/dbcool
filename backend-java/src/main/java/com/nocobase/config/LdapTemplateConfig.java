package com.nocobase.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.Context;
import java.util.HashMap;
import java.util.Map;

/**
 * LdapTemplate Bean 配置 — 确保 LdapSyncService 能注入 LdapTemplate，应用可无 LDAP 环境启动。
 *
 * <p>未配置真实 LDAP 时使用占位 URL (ldap://localhost:389)，Bean 可以创建；
 * 只有真正调用 LdapTemplate 方法（如 syncUsers）时才会尝试连接，
 * 此时若无真实 LDAP 服务器会抛出明确异常，而非伪造用户数据（遵守"禁止假成功"红线）。
 */
@Configuration
@ConditionalOnClass(LdapTemplate.class)
public class LdapTemplateConfig {

    @Bean
    @ConditionalOnMissingBean(LdapContextSource.class)
    public LdapContextSource ldapContextSource() {
        Map<String, Object> props = new HashMap<>();
        props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        props.put(Context.PROVIDER_URL, "ldap://localhost:389");
        props.put(Context.SECURITY_AUTHENTICATION, "none");
        
        LdapContextSource source = new LdapContextSource();
        source.setBase("dc=example,dc=com");
        source.setUrls(new String[]{"ldap://localhost:389"});
        source.setPooled(false);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(LdapTemplate.class)
    public LdapTemplate ldapTemplate(LdapContextSource contextSource) {
        return new LdapTemplate(contextSource);
    }
}
