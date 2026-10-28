package com.nocobase.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 支付网关注册表 (Payment Gateway Registry).
 *
 * <p>汇聚两类网关：</p>
 * <ol>
 *   <li>Spring 容器中的 {@link PaymentGateway} Bean（如 {@link ManualPaymentGateway}）；</li>
 *   <li>依据 {@code nocobase.billing.payment.gateways.<name>.url} 配置构造的 {@link HttpPaymentGateway}。</li>
 * </ol>
 *
 * <p>未配置的支付方式不会被静默放行：{@link #resolve(String)} 直接抛
 * {@link UnsupportedPaymentProviderException}，由 Controller 转成 400。</p>
 */
@Component
public class PaymentGatewayRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayRegistry.class);

    private final Map<String, PaymentGateway> gateways = new LinkedHashMap<>();

    public PaymentGatewayRegistry(List<PaymentGateway> beans,
                                  BillingPaymentProperties properties,
                                  RestTemplateBuilder restTemplateBuilder,
                                  ObjectMapper objectMapper) {
        for (PaymentGateway gateway : beans) {
            gateways.put(normalize(gateway.provider()), gateway);
        }

        Map<String, BillingPaymentProperties.GatewaySettings> configured =
                properties == null || properties.getGateways() == null
                        ? Map.of() : properties.getGateways();

        configured.forEach((name, settings) -> {
            if (settings == null || !settings.isEnabled() || !StringUtils.hasText(settings.getUrl())) {
                return;
            }
            HttpPaymentGateway gateway = new HttpPaymentGateway(
                    name, settings.getUrl(), settings.getApiKey(),
                    restTemplateBuilder.build(), objectMapper);
            gateways.put(normalize(name), gateway);
        });

        log.info("Payment gateways registered: {}", gateways.keySet());
    }

    /** 按支付方式解析网关；未注册则抛错，绝不回退到"假装成功"。 */
    public PaymentGateway resolve(String provider) {
        PaymentGateway gateway = StringUtils.hasText(provider)
                ? gateways.get(normalize(provider)) : null;
        if (gateway == null) {
            throw new UnsupportedPaymentProviderException(provider, gateways.keySet());
        }
        return gateway;
    }

    /** 已注册网关标识，用于错误提示与前端可选支付方式下拉。 */
    public Set<String> supportedProviders() {
        return Set.copyOf(gateways.keySet());
    }

    private static String normalize(String provider) {
        return provider.toLowerCase(Locale.ROOT);
    }
}
