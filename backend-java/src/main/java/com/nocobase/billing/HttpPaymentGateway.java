package com.nocobase.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 支付网关 (Http Payment Gateway).
 *
 * <p>面向支付宝/微信支付/Stripe 这一类"下单即扣款"的 HTTP 接口：向配置的
 * {@code url} POST 订单，按响应判定成功/失败。仅当网关明确返回成功态时才
 * 返回 {@code success=true}；网络异常、非 2xx、响应不可解析一律判为失败，
 * 并把原始响应写入 PaymentEntity.metadata 供对账。</p>
 *
 * <p>响应约定（各渠道网关/自建支付中间件需按此返回）：</p>
 * <pre>
 * {"status": "succeeded"|"failed"|"pending", "transaction_id": "...", "message": "..."}
 * </pre>
 */
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private final String provider;
    private final String url;
    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpPaymentGateway(String provider, String url, String apiKey,
                              RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.provider = provider;
        this.url = url;
        this.apiKey = apiKey;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("order_id", request.invoiceId());
        body.put("subscription_id", request.subscriptionId());
        body.put("tenant_id", request.tenantId());
        body.put("amount", request.amount() == null ? null : request.amount().toPlainString());
        body.put("currency", request.currency());
        body.put("subject", request.orderTitle());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKey)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        String raw;
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return PaymentResult.failed(
                        "网关返回 HTTP " + response.getStatusCode().value(), response.getBody());
            }
            raw = response.getBody();
        } catch (RestClientException e) {
            log.error("Payment gateway {} charge failed", provider, e);
            return PaymentResult.failed("调用支付网关失败: " + e.getMessage(), null);
        }

        if (!StringUtils.hasText(raw)) {
            return PaymentResult.failed("支付网关返回空响应", null);
        }

        try {
            JsonNode node = objectMapper.readTree(raw);
            String status = node.path("status").asText("");
            String transactionId = node.path("transaction_id").asText(null);
            String message = node.path("message").asText(null);

            if ("succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                return PaymentResult.succeeded(transactionId, raw);
            }
            if ("pending".equalsIgnoreCase(status)) {
                return PaymentResult.pending(
                        StringUtils.hasText(message) ? message : "网关已受理，等待收款确认");
            }
            return PaymentResult.failed(
                    StringUtils.hasText(message) ? message : "网关未返回成功态: " + status, raw);
        } catch (Exception e) {
            log.error("Unparsable payment gateway response from {}", provider, e);
            return PaymentResult.failed("支付网关响应无法解析: " + e.getMessage(), raw);
        }
    }
}
