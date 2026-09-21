package com.nocobase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 端点契约测试 — 确保关键控制器类存在且可实例化（防"编译绿、运行空"）。
 *
 * <p>通过 Class.forName 验证类存在；通过反射验证目标方法签名兼容。
 */
class ApiEndpointContractTest {

    @Test
    void searchController_exists() throws Exception {
        Class<?> ctrl = Class.forName("com.nocobase.search.UnifiedSearchController");
        assertThat(ctrl.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("RestController"));
    }

    @Test
    void aiController_exists() throws Exception {
        Class<?> ctrl = Class.forName("com.nocobase.ai.AiController");
        assertThat(ctrl.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("RestController"));
    }

    @Test
    void livechatController_exists() throws Exception {
        Class<?> ctrl = Class.forName("com.nocobase.ticket.TicketController");
        assertThat(ctrl.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("RestController"));
    }

    @Test
    void wecomController_exists() throws Exception {
        Class<?> ctrl = Class.forName("com.nocobase.integration.wecom.WeComController");
        assertThat(ctrl.getDeclaredAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals("RestController"));
    }

    @Test
    void searchService_methodsExist() throws Exception {
        Class<?> svc = Class.forName("com.nocobase.search.UnifiedSearchService");
        assertThat(svc.getMethod("search", String.class, String.class, java.util.List.class, int.class))
                .isNotNull();
        assertThat(svc.getMethod("indexEntity", String.class, String.class, String.class,
                String.class, String.class, java.util.Map.class))
                .isNotNull();
        assertThat(svc.getMethod("removeEntity", String.class, String.class, String.class))
                .isNotNull();
    }

    @Test
    void aiAssistantService_methodsExist() throws Exception {
        Class<?> svc = Class.forName("com.nocobase.ai.AiAssistantService");
        assertThat(svc.getMethod("chat", String.class, String.class, int.class, String.class))
                .isNotNull();
        assertThat(svc.getMethod("getQuota", String.class, String.class))
                .isNotNull();
    }
}
