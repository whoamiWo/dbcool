package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** HTTP 支付网关单测：真实走 RestTemplate，验证请求头/体与三态结果映射。 */
class HttpPaymentGatewayTest {

    private static final String URL = "https://pay.example.com/v1/charge";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private HttpPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        gateway = new HttpPaymentGateway("alipay", URL, "secret-key", restTemplate, new ObjectMapper());
    }

    private PaymentGateway.ChargeRequest request() {
        return new PaymentGateway.ChargeRequest("t1", "inv-1", "sub-1",
                new BigDecimal("99.00"), "CNY", "NocoBase 账单 INV-1");
    }

    @Test
    @DisplayName("网关返回 succeeded → 成功并回填交易号")
    void charge_succeeded() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
                .andExpect(jsonPath("$.order_id").value("inv-1"))
                .andExpect(jsonPath("$.amount").value("99.00"))
                .andRespond(withSuccess("{\"status\":\"succeeded\",\"transaction_id\":\"tx-1\"}",
                        MediaType.APPLICATION_JSON));

        PaymentGateway.PaymentResult result = gateway.charge(request());

        assertThat(result.success()).isTrue();
        assertThat(result.transactionId()).isEqualTo("tx-1");
        server.verify();
    }

    @Test
    @DisplayName("网关返回 failed → 失败并保留原因")
    void charge_failed() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(
                        "{\"status\":\"failed\",\"message\":\"余额不足\"}", MediaType.APPLICATION_JSON));

        PaymentGateway.PaymentResult result = gateway.charge(request());

        assertThat(result.state()).isEqualTo(PaymentGateway.PaymentResult.State.FAILED);
        assertThat(result.failureReason()).isEqualTo("余额不足");
    }

    @Test
    @DisplayName("网关返回 pending → 待确认，不得视为成功")
    void charge_pending_isNotSuccess() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"status\":\"pending\"}", MediaType.APPLICATION_JSON));

        PaymentGateway.PaymentResult result = gateway.charge(request());

        assertThat(result.state()).isEqualTo(PaymentGateway.PaymentResult.State.PENDING);
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("HTTP 5xx → 失败，不抛异常穿透")
    void charge_serverError() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        PaymentGateway.PaymentResult result = gateway.charge(request());

        assertThat(result.state()).isEqualTo(PaymentGateway.PaymentResult.State.FAILED);
        assertThat(result.failureReason()).contains("500");
    }

    @Test
    @DisplayName("响应无法解析 → 失败并保留原始报文")
    void charge_unparsableResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        PaymentGateway.PaymentResult result = gateway.charge(request());

        assertThat(result.state()).isEqualTo(PaymentGateway.PaymentResult.State.FAILED);
        assertThat(result.failureReason()).contains("无法解析");
        assertThat(result.rawResponse()).contains("oops");
    }
}
