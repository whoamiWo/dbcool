package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.notification.NotifyDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * A0-3：生产 Spring context 必须真实注入 {@link WorkflowFailureNotifier}。
 *
 * <p>之前 6 个执行入口用 {@code @Autowired(required=false)} + {@code if != null}，
 * Bean 缺失时通知静默消失，测试也不会失败。这里用最小 Spring context 断言
 * notifier 可被注入；6 个入口的字段声明也做静态校验（required，非 optional）。
 */
@SpringJUnitConfig(classes = WorkflowFailureNotifierInjectionTest.Config.class)
class WorkflowFailureNotifierInjectionTest {

    @Autowired
    private WorkflowFailureNotifier notifier;

    @Test
    void notifier_isInjectedByContext() {
        assertThat(notifier).isNotNull();
    }

    @Test
    void allSixEntrances_requireNotifierInjection() throws Exception {
        Class<?>[] entrances = {
                WorkflowController.class,
                WorkflowTriggerListener.class,
                WorkflowScheduler.class,
                WorkflowTaskService.class,
                com.nocobase.approval.ApprovalService.class,
                com.nocobase.playbook.PlaybookService.class,
        };
        for (Class<?> type : entrances) {
            var field = findField(type);
            assertThat(field).as(type.getSimpleName() + " 缺少 failureNotifier 字段").isNotNull();
            boolean constructorInjected = java.lang.reflect.Modifier.isFinal(
                    field.getModifiers());
            if (!constructorInjected) {
                var autowired = field.getAnnotation(
                        org.springframework.beans.factory.annotation.Autowired.class);
                assertThat(autowired).as(type.getSimpleName() + " 必须使用 @Autowired").isNotNull();
                assertThat(autowired.required())
                        .as(type.getSimpleName() + " 不允许 optional notifier（A0-3）")
                        .isTrue();
            }
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type) {
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (f.getType() == WorkflowFailureNotifier.class) return f;
        }
        return null;
    }

    @Configuration
    static class Config {
        @Bean
        WorkflowRepository workflowRepository() {
            return mock(WorkflowRepository.class);
        }

        @Bean
        NotifyDispatcher notifyDispatcher() {
            return mock(NotifyDispatcher.class);
        }

        @Bean
        WorkflowFailureNotifier workflowFailureNotifier(WorkflowRepository repo,
                                                        NotifyDispatcher dispatcher) {
            return new WorkflowFailureNotifier(repo, dispatcher);
        }
    }
}
