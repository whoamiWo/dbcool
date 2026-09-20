package com.nocobase.search;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 跨模块统一搜索 REST API。
 */
@RestController
@Tag(name = "Unified Search", description = "跨模块统一搜索")
@RequestMapping("/api/search")
public class UnifiedSearchController {

    private final UnifiedSearchService searchService;

    public UnifiedSearchController(UnifiedSearchService searchService) {
        this.searchService = searchService;
    }

    /** 统一搜索 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) List<String> types,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal AuthenticatedUser user) {
        
        Map<String, Object> result = searchService.search(
                keyword, user.tenantId(), types, limit);
        
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", result
        ));
    }
}
