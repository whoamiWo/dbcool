package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/** 支付网关注册表：渠道注册、解析与"未注册即拒绝"。 */
class PaymentGatewayRegistryTest {

    private final BillingPaymentProperties properties = new BillingPaymentProperties();
    private final RestTemplateBuilder builder = new RestTemplateBuilder();

    private PaymentGatewayRegistry registry(List<PaymentGateway> beans) {
        return new PaymentGatewayRegistry(beans, properties, builder, new ObjectMapper());
    }

    @Test
    @DisplayName("Bean 形式的网关被注册且大小写不敏感")
    void registersBeanGateways() {
        PaymentGatewayRegistry registry = registry(List.of(new ManualPaymentGateway()));

        assertThat(registry.supportedProviders()).containsExactly("manual");
        assertThat(registry.resolve("MANUAL").provider()).isEqualTo(ManualPaymentGateway.PROVIDER);
    }

    @Test
    @DisplayName("配置了 url 的网关被注册；未配置 url 的不注册")
    void registersConfiguredHttpGateways() {
        BillingPaymentProperties.GatewaySettings alipay = new BillingPaymentProperties.GatewaySettings();
        alipay.setUrl("https://pay.example.com/alipay");
        alipay.setApiKey("k");
        BillingPaymentProperties.GatewaySettings disabled = new BillingPaymentProperties.GatewaySettings();
        disabled.setUrl("https://pay.example.com/wechat");
        disabled.setEnabled(false);
        properties.setGateways(java.util.Map.of("alipay", alipay, "wechat", disabled));

        PaymentGatewayRegistry registry = registry(List.of(new ManualPaymentGateway()));

        assertThat(registry.supportedProviders()).containsExactlyInAnyOrder("manual", "alipay");
        assertThat(registry.resolve("alipay")).isInstanceOf(HttpPaymentGateway.class);
    }

    @Test
    @DisplayName("未注册的支付方式 → 抛 UnsupportedPaymentProviderException（绝不静默成功）")
    void unknownProvider_throws() {
        PaymentGatewayRegistry registry = registry(List.of(new ManualPaymentGateway()));

        assertThatThrownBy(() -> registry.resolve("bitcoin"))
                .isInstanceOf(UnsupportedPaymentProviderException.class)
                .hasMessageContaining("bitcoin");
        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnsupportedPaymentProviderException.class);
    }
}
