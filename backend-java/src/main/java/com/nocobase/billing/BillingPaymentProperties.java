package com.nocobase.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付网关配置 ({@code nocobase.billing.payment.gateways.*}).
 *
 * <pre>
 * nocobase:
 *   billing:
 *     payment:
 *       gateways:
 *         alipay:
 *           url: https://openapi.alipay.com/v3/pay
 *           api-key: ${ALIPAY_API_KEY}
 *         wechat: { url: https://api.mch.weixin.qq.com/v3/pay, api-key: ${WECHAT_API_KEY} }
 *         stripe: { url: https://api.stripe.com/v1/charges, api-key: ${STRIPE_API_KEY} }
 * </pre>
 *
 * <p>未配置 url 的网关不会被注册，对应支付方式在支付时直接报错。</p>
 */
@Component
@ConfigurationProperties(prefix = "nocobase.billing.payment")
public class BillingPaymentProperties {

    private Map<String, GatewaySettings> gateways = new LinkedHashMap<>();

    public Map<String, GatewaySettings> getGateways() {
        return gateways;
    }

    public void setGateways(Map<String, GatewaySettings> gateways) {
        this.gateways = gateways == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gateways);
    }

    /** 单个网关配置。 */
    public static class GatewaySettings {

        /** 网关扣款端点，必填；为空则该网关不注册。 */
        private String url;

        /** 网关 API Key，通过 {@code Authorization: Bearer} 传递。 */
        private String apiKey;

        private boolean enabled = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
