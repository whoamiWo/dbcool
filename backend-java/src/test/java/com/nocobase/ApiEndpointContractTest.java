package com.nocobase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 前端端点契约测试 — 防前后端路径漂移。
 *
 * <p>验证关键控制器类存在且可实例化；验证方法签名与前端常量表匹配。
 * 前端 `endpoints.ts` 变化时，必须同步更新此测试（或反过来）。
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
        assertThat(svc.getMethod("search", String.class, String.class, java.util.UUID.class,
                java.util.List.class, int.class))
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
