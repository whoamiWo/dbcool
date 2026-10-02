package com.nocobase.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * PHASE69 T3: storageKey 含斜杠 (tenantId/uuid/filename),
 * Spring Boot 默认 PathPatternParser 不支持 {*var} 后跟其他路径段。
 * 改用 AntPathMatcher 并允许编码斜杠。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(false);
        configurer.setUrlPathHelper(helper);
    }
}

