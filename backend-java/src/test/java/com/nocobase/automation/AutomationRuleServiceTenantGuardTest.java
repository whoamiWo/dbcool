package com.nocobase.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.automation.repository.AutomationExecutionRepository;
import com.nocobase.automation.repository.AutomationRuleRepository;
import com.nocobase.meta.CollectionService;
import com.nocobase.notification.NotificationService;
import com.nocobase.notification.WebhookDispatcher;
import com.nocobase.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE87：自动化规则的租户来源必须是**显式参数**，不能依赖 ThreadLocal。
 *
 * <p><b>为什么这条要单独测</b>：本批排查出的真风险点是
 * {@code AutomationTriggerListener.onRecordChange()} 起了 {@code @Async} 链路，
 * 而 ThreadLocal <b>不会跨线程继承</b> —— 异步路径上
 * {@code TenantContext.currentTenantId()} 会静默回退成默认租户
 * （不是报错，是悄悄读/写错租户的数据）。
 *
 * <p><b>本测试锁定两件事</b>：
 * <ol>
 *   <li>查询用的是<b>传入的</b> tenantId，不是 ThreadLocal 里的值；</li>
 *   <li>即使 ThreadLocal 已设为别的租户，也不影响结果（显式参数优先）。</li>
 * </ol>
 */
class AutomationRuleServiceTenantGuardTest {

    private AutomationRuleRepository ruleRepository;
    private AutomationRuleService service;

    private final UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutomationRuleRepository.class);
        service = new AutomationRuleService(
                ruleRepository,
                mock(AutomationExecutionRepository.class),
                mock(CollectionService.class),
                mock(NotificationService.class),
                mock(WebhookDispatcher.class),
                new ObjectMapper());

        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        when(ruleRepository.findByIdAndTenantId(ruleId, "tenant_B")).thenReturn(Optional.of(rule));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void getRule_usesExplicitTenantId_notThreadLocal() {
        // ThreadLocal 故意设成**另一个**租户，模拟异步线程残留/错误上下文
        TenantContext.set("tenant_A");

        service.getRule(ruleId, "tenant_B");

        // 必须按显式参数查询；若实现偷用 currentTenantId()，这里会是 tenant_A → 失败
        verify(ruleRepository).findByIdAndTenantId(ruleId, "tenant_B");
        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_A"); // 入参未被 ThreadLocal 污染
    }

    @Test
    void getRule_wrongTenant_throws() {
        TenantContext.set("tenant_B");

        // 传了租户 C：repository 查不到 → 应抛"规则不存在或无权访问"，
        // 绝不因为 ThreadLocal 里有个 tenant_B 就把规则返回出去
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getRule(ruleId, "tenant_C"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无权访问");
    }
}
