package com.nocobase.ai;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * AI 助手 REST API — 文档生成 / 问答 / 代码辅助 / 提示词模板。
 *
 * <p>请求转发至 Python LLM 网关,用户 JWT 由请求头透传(配额按真实用户记账)。
 * 未启用或网关异常时返回提示文案,HTTP 始终 200,不阻断前端流程。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAssistantService aiService;
    private final AiPromptTemplateService promptService;

    public AiController(AiAssistantService aiService, AiPromptTemplateService promptService) {
        this.aiService = aiService;
        this.promptService = promptService;
    }

    /** 生成 Wiki 文档:body = {title, instruction} */
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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

    /** AI 能力状态(供前端判断是否展示入口) */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "enabled", aiService.isEnabled(),
                        "model", aiService.getModel(),
                        "models", aiService.getModels()
                )
        ));
    }

    /**
     * 统一 AI 对话入口 — 转发至 Python LLM 网关(/api/ai/chat)，
     * 复用其限流/缓存/配额三层防护。
     *
     * <p>请求体: {prompt, model?, max_tokens?}
     * <p>响应体: {code, message, data:{result, cached, tokensUsed, quota:{remaining, daily, monthly}}}
     */
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/quota")
    public Map<String, Object> quota(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth
    ) {
        return aiService.getQuota(user.userId().toString(), auth);
    }

    // ============================================================
    //  C4: PromptTemplate CRUD
    // ============================================================

    /** 创建提示词模板。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/prompt-templates")
    public ResponseEntity<Map<String, Object>> createPromptTemplate(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String promptTemplate = (String) body.get("prompt_template");
        String variablesJson = (String) body.get("variables_json");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "name 必填"));
        }
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "prompt_template 必填"));
        }

        var entity = promptService.createTemplate(tenantId, name, description, promptTemplate, variablesJson);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", templateToMap(entity)));
    }

    /** 更新提示词模板。 */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/prompt-templates/{id}")
    public ResponseEntity<Map<String, Object>> updatePromptTemplate(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Optional<AiPromptTemplateEntity> opt = promptService.getTemplate(id, tenantId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "模板不存在或无权访问"));
        }

        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String promptTemplate = (String) body.get("prompt_template");
        String variablesJson = (String) body.get("variables_json");

        var updated = promptService.updateTemplate(id, name, description, promptTemplate, variablesJson);
        return updated.map(e -> ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", templateToMap(e))))
                .orElse(ResponseEntity.status(404).body(Map.of("code", 404, "message", "更新失败")));
    }

    /** 删除提示词模板。 */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/prompt-templates/{id}")
    public ResponseEntity<Map<String, Object>> deletePromptTemplate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Optional<AiPromptTemplateEntity> opt = promptService.getTemplate(id, tenantId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "模板不存在或无权访问"));
        }
        boolean deleted = promptService.deleteTemplate(id);
        if (!deleted) {
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", "删除失败"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    /** 列出提示词模板。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/prompt-templates")
    public ResponseEntity<Map<String, Object>> listPromptTemplates(
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        List<AiPromptTemplateEntity> list;
        if (search != null && !search.isBlank()) {
            list = promptService.searchTemplates(tenantId, search);
        } else {
            list = promptService.listTemplates(tenantId);
        }
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", list.stream().map(AiController::templateToMap).toList()
        ));
    }

    /** 获取单个提示词模板。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/prompt-templates/{id}")
    public ResponseEntity<Map<String, Object>> getPromptTemplate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Optional<AiPromptTemplateEntity> opt = promptService.getTemplate(id, tenantId);
        return opt.map(e -> ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", templateToMap(e))))
                .orElse(ResponseEntity.status(404).body(Map.of("code", 404, "message", "模板不存在或无权访问")));
    }

    /** 填充提示词变量。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/prompt-templates/{id}/fill")
    public ResponseEntity<Map<String, Object>> fillPromptTemplate(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Optional<AiPromptTemplateEntity> opt = promptService.getTemplate(id, tenantId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "模板不存在或无权访问"));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> values = (Map<String, String>) body.get("values");
        String filled = promptService.fillVariables(opt.get().getPromptTemplate(), values);

        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", Map.of("filled_prompt", filled)));
    }

    /** 提取模板中的变量列表。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/prompt-templates/{id}/variables")
    public ResponseEntity<Map<String, Object>> extractTemplateVariables(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String tenantId = user.tenantId();
        Optional<AiPromptTemplateEntity> opt = promptService.getTemplate(id, tenantId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "模板不存在或无权访问"));
        }
        List<String> variables = promptService.extractVariables(opt.get().getPromptTemplate());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", Map.of("variables", variables)));
    }

    private static Map<String, Object> templateToMap(AiPromptTemplateEntity e) {
        return Map.of(
                "id", e.getId().toString(),
                "tenant_id", e.getTenantId(),
                "name", e.getName(),
                "description", e.getDescription(),
                "prompt_template", e.getPromptTemplate(),
                "variables_json", e.getVariablesJson(),
                "created_at", e.getCreatedAt().toString(),
                "updated_at", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null
        );
    }
}