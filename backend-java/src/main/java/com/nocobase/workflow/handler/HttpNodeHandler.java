package com.nocobase.workflow.handler;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class HttpNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpNodeHandler.class);
    private final RestTemplate restTemplate;

    public HttpNodeHandler() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        String method = (String) config.getOrDefault("method", "POST");
        String url = (String) config.get("url");
        Object body = config.get("body");

        if (url == null || url.isBlank()) {
            log.warn("[workflow {} node {}] HTTP node missing url",
                    ctx.instance().getId(), ctx.node().get("id"));
            return NodeOutcome.FAILED;
        }

        HttpHeaders headers = new HttpHeaders();
        if (config.get("headers") instanceof Map) {
            // 用 set 而非 add(对齐 legacy):同名 header 应覆盖而不是追加
            ((Map<String, String>) config.get("headers")).forEach(headers::set);
        }

        // 鉴权:config.auth.type = bearer | basic | none
        // 对齐 legacy WorkflowEngine.executeHttp()(:394-404),Week 41 初版完全没读 auth
        if (config.get("auth") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = (Map<String, Object>) config.get("auth");
            String authType = (String) auth.getOrDefault("type", "none");
            if ("bearer".equals(authType)) {
                headers.setBearerAuth(String.valueOf(auth.getOrDefault("token", "")));
            } else if ("basic".equals(authType)) {
                headers.setBasicAuth(
                        String.valueOf(auth.getOrDefault("username", "")),
                        String.valueOf(auth.getOrDefault("password", "")));
            }
        }

        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            // toUpperCase 对齐 legacy:小写 method(如 "get")会让 HttpMethod.valueOf 抛 IllegalArgumentException
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(method.toUpperCase()), entity, String.class);
            log.info("[workflow {}] HTTP {} {} → {}",
                    ctx.instance().getId(), method, url, response.getStatusCode());
            return NodeOutcome.CONTINUE;
        } catch (RestClientException e) {
            // 报告 4.3.3:HTTP 失败可配置中断或重试。Week 41 默认 CONTINUE + 错误日志
            log.error("[workflow {}] HTTP call failed: {}", ctx.instance().getId(), e.getMessage());
            return NodeOutcome.CONTINUE;
        }
    }
}
