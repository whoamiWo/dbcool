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
}