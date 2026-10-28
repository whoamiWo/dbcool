package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * B4 计费「默认关闭」契约守护。
 *
 * <p>项目定位为个人自用、非商业化的平台，计费/订阅/支付属于可选能力。
 * 本测试锁住"必须显式 {@code billing.enabled=true} 才装配"这条约定：
 * 一旦有人摘掉 {@link ConditionalOnProperty}（或把 matchIfMissing 改成 true），
 * 计费端点就会默认暴露，这里立刻变红。</p>
 */
class BillingDisabledGuardTest {

    @Test
    @DisplayName("计费相关 Bean 必须由 billing.enabled=true 显式开启")
    void billingBeansAreGated() {
        for (Class<?> type : new Class<?>[]{
                BillingService.class,
                BillingController.class,
                BillingOverdueJob.class,
                PaymentGatewayRegistry.class,
                ManualPaymentGateway.class}) {

            ConditionalOnProperty ann = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(ann)
                    .as("%s 必须带 @ConditionalOnProperty，否则计费能力会默认装配", type.getSimpleName())
                    .isNotNull();
            assertThat(ann.prefix()).isEqualTo("billing");
            assertThat(ann.name()).containsExactly("enabled");
            assertThat(ann.havingValue()).isEqualTo("true");
            assertThat(ann.matchIfMissing())
                    .as("%s 不得在 billing.enabled 缺失时装配", type.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("配置属性前缀与 application.yml 的 billing.payment 对齐")
    void paymentPropertiesPrefixMatchesConfig() {
        assertThat(BillingPaymentProperties.class.getAnnotation(
                org.springframework.boot.context.properties.ConfigurationProperties.class).prefix())
                .isEqualTo("billing.payment");
    }
}
