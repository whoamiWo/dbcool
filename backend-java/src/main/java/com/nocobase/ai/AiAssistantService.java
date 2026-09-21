package com.nocobase.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Function;

/**
 * AI 助手服务 — 对接 Python LLM 网关({@code POST /api/ai/chat})。
 *
 * <p><b>Week 44 接真</b>:此前三个方法均为 fallback 占位文案;现通过内部 HTTP
 * 调用 Python 侧网关,复用其已有的三层防护(限流 / 缓存 / 配额),
 * 避免 Java 与 Python 两套限流口径不一致。
 *
 * <p><b>认证</b>:转发调用方 JWT(Python 网关用同一套密钥校验),
 * 因此配额与限流按真实用户记账。
 *
 * <p><b>降级</b>:{@code ai.enabled=false} 或网关调用失败时返回提示文案,
 * 不抛异常、不阻断业务主流程。
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);
    private static final String UNAVAILABLE =
            "AI 助手暂未启用或不可用。请联系管理员配置 AI 服务(ai.enabled / ai.python-url)。";

    private final RestTemplate rest = new RestTemplate();

    @Value("${ai.enabled:false}")
    private boolean enabled;

    /** Python 网关地址(默认本机 8000,与 docker-compose PYTHON_PORT 一致)。 */
    @Value("${ai.python-url:http://localhost:8000}")
    private String pythonUrl;

    @Value("${ai.model:gpt-4o}")
    private String model;

    @Value("${ai.max-tokens:1024}")
    private int maxTokens;

    /**
     * 生成 Wiki 文档内容。
     *
     * @return {content: Markdown, html: 简易 HTML}
     */
    public Map<String, Object> generateWikiContent(String title, String instruction, String bearerToken) {
        String prompt = "请撰写一篇企业内部 Wiki 文档。\n标题:" + title
                + "\n补充要求:" + (instruction == null ? "无" : instruction)
                + "\n请直接输出 Markdown 正文,不要包含额外解释。";
        return callLlm(prompt, bearerToken, text -> Map.of(
                "content", text,
                "html", toSimpleHtml(text)
        ));
    }

    /** AI 问答。 */
    public Map<String, Object> askQuestion(String question, String context, String bearerToken) {
        String prompt = (context == null || context.isBlank())
                ? question
                : "基于以下上下文回答问题。\n\n上下文:\n" + context + "\n\n问题:" + question;
        return callLlm(prompt, bearerToken, text -> Map.of("answer", text));
    }

    /** 代码辅助生成。 */
    public Map<String, Object> generateCode(String instruction, String language, String bearerToken) {
        String prompt = "请用 " + (language == null ? "Java" : language)
                + " 实现以下需求,只输出代码,不要解释。\n需求:" + instruction;
        return callLlm(prompt, bearerToken, text -> Map.of("code", text));
    }

    // ============================================================
    //  网关调用 + 降级
    // ============================================================

    /** 调用 Python LLM 网关;未启用或失败时降级为提示文案。 */
    private Map<String, Object> callLlm(String prompt, String bearerToken,
                                         Function<String, Map<String, Object>> mapper) {
        if (!enabled) {
            return Map.of("code", 0, "message", "AI 未启用", "data", mapper.apply(UNAVAILABLE));
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null && !bearerToken.isBlank()) {
                headers.setBearerAuth(stripBearer(bearerToken));
            }
            Map<String, Object> body = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "max_tokens", maxTokens
            );
            ResponseEntity<Map> resp = rest.postForEntity(
                    pythonUrl + "/api/ai/chat",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            Map<?, ?> payload = resp.getBody();
            Object respObj = payload == null ? null : payload.get("response");
            String text = respObj == null ? "" : String.valueOf(respObj);
            return Map.of("code", 0, "message", "success", "data", mapper.apply(text));
        } catch (Exception e) {
            log.warn("[AI] LLM 网关调用失败,降级返回提示: {}", e.getMessage());
            return Map.of("code", 0, "message", "AI 服务不可用", "data", mapper.apply(UNAVAILABLE));
        }
    }

    /** 去掉 "Bearer " 前缀(若存在)。 */
    private static String stripBearer(String token) {
        String t = token.trim();
        return t.toLowerCase().startsWith("bearer ") ? t.substring(7).trim() : t;
    }

    /** Markdown → 极简 HTML(标题/段落/代码块),供阅读页渲染。 */
    private static String toSimpleHtml(String md) {
        if (md == null || md.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;
        for (String line : md.split("\n")) {
            if (line.startsWith("```")) {
                sb.append(inCode ? "</code></pre>" : "<pre><code>").append("\n");
                inCode = !inCode;
                continue;
            }
            if (inCode) { sb.append(line).append("\n"); continue; }
            if (line.startsWith("### ")) sb.append("<h3>").append(line.substring(4)).append("</h3>");
            else if (line.startsWith("## ")) sb.append("<h2>").append(line.substring(3)).append("</h2>");
            else if (line.startsWith("# ")) sb.append("<h1>").append(line.substring(2)).append("</h1>");
            else if (line.isBlank()) sb.append("<br/>");
            else sb.append("<p>").append(line).append("</p>");
        }
        if (inCode) sb.append("</code></pre>");
        return sb.toString();
    }

    public boolean isEnabled() { return enabled; }
    public String getModel() { return model; }
    public int getMaxTokens() { return maxTokens; }

    /**
     * 统一对话入口 — 转发至 Python /api/ai/chat，复用限流/缓存/配额。
     *
     * @return {code, message, data:{result, cached, tokensUsed, quota}}
     */
    public Map<String, Object> chat(String prompt, String model, int maxTokens, String bearerToken) {
        return callLlm(prompt, bearerToken, text -> {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("result", text);
            return data;
        });
    }

    /**
     * 配额查询 — 通过 Python /api/ai/quota_info 端点获取。
     * 未启用时返回明确错误提示。
     */
    public Map<String, Object> getQuota(String userId, String bearerToken) {
        if (!enabled) {
            return Map.of(
                    "code", 0,
                    "message", "AI 未启用",
                    "data", Map.of("remaining", 0, "daily", 0, "monthly", 0)
            );
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null && !bearerToken.isBlank()) {
                headers.setBearerAuth(stripBearer(bearerToken));
            }
            ResponseEntity<Map> resp = rest.getForEntity(
                    pythonUrl + "/api/ai/quota_info?user_id=" + userId,
                    Map.class,
                    headers
            );
            Map<?, ?> payload = resp.getBody();
            if (payload == null) {
                return Map.of("code", 0, "message", "success",
                        "data", Map.of("remaining", 0, "daily", 0, "monthly", 0));
            }
            Object remaining = payload.get("remaining");
            Object daily = payload.get("daily");
            Object monthly = payload.get("monthly");
            return Map.of(
                    "code", 0,
                    "message", "success",
                    "data", Map.of(
                            "remaining", remaining != null ? remaining : 0,
                            "daily", daily != null ? daily : 0,
                            "monthly", monthly != null ? monthly : 0
                    )
            );
        } catch (Exception e) {
            log.warn("[AI] 配额查询失败 userId={} error={}", userId, e.getMessage());
            return Map.of(
                    "code", 0,
                    "message", "success",
                    "data", Map.of("remaining", 0, "daily", 0, "monthly", 0)
            );
        }
    }
}