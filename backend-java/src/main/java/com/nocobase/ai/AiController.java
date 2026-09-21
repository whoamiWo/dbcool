package com.nocobase.ai;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 助手 REST API — 文档生成 / 问答 / 代码辅助。
 *
 * <p>请求转发至 Python LLM 网关,用户 JWT 由请求头透传(配额按真实用户记账)。
 * 未启用或网关异常时返回提示文案,HTTP 始终 200,不阻断前端流程。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAssistantService aiService;

    public AiController(AiAssistantService aiService) {
        this.aiService = aiService;
    }

    /** 生成 Wiki 文档:body = {title, instruction} */
    @PostMapping("/wiki")
    public Map<String, Object> generateWiki(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String title = (String) body.get("title");
        String instruction = (String) body.get("instruction");
        if (title == null || title.isBlank()) {
            return Map.of("code", 400, "message", "title 必填", "data", Map.of());
        }
        return aiService.generateWikiContent(title, instruction, auth);
    }

    /** AI 问答:body = {question, context} */
    @PostMapping("/ask")
    public Map<String, Object> ask(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            return Map.of("code", 400, "message", "question 必填", "data", Map.of());
        }
        String context = (String) body.get("context");
        return aiService.askQuestion(question, context, auth);
    }

    /** 代码辅助:body = {instruction, language} */
    @PostMapping("/code")
    public Map<String, Object> code(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String instruction = (String) body.get("instruction");
        if (instruction == null || instruction.isBlank()) {
            return Map.of("code", 400, "message", "instruction 必填", "data", Map.of());
        }
        String language = (String) body.getOrDefault("language", "Java");
        return aiService.generateCode(instruction, language, auth);
    }

    /** AI 能力状态(供前端判断是否展示入口)。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of("enabled", aiService.isEnabled(), "model", aiService.getModel())
        ));
    }

    /**
     * 统一 AI 对话入口 — 转发至 Python LLM 网关(/api/ai/chat)，
     * 复用其限流/缓存/配额三层防护。
     *
     * <p>请求体: {prompt, model?, max_tokens?}
     * <p>响应体: {code, message, data:{result, cached, tokensUsed, quota:{remaining, daily, monthly}}}
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String prompt = (String) body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return Map.of("code", 400, "message", "prompt 必填", "data", Map.of());
        }
        String model = (String) body.getOrDefault("model", aiService.getModel());
        int maxTokens = body.containsKey("max_tokens")
                ? ((Number) body.get("max_tokens")).intValue()
                : aiService.getMaxTokens();
        return aiService.chat(prompt, model, maxTokens, auth);
    }

    /**
     * 配额查询 — 返回当前用户的剩余调用次数与 token 额度。
     * 未启用时返回明确提示。
     */
    @GetMapping("/quota")
    public Map<String, Object> quota(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth
    ) {
        return aiService.getQuota(user.userId().toString(), auth);
    }
}