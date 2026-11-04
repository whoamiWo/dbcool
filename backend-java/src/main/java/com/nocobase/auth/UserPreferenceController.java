package com.nocobase.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户偏好（E3 通知设置来源）。
 *
 * <p>复用既有 {@code user_preferences} 表（D2：不新建表）：
 * <pre>
 * {"notify": {"APPROVAL_TODO": ["EMAIL","DINGTALK"], "quietHours": ["22:00","08:00"]}}
 * </pre>
 * {@code NotifyDispatcher} 读取同一结构决定是否投递外部渠道与免打扰时段。
 */
@RestController
@RequestMapping("/api/users/me/preferences")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Preferences", description = "用户偏好")
public class UserPreferenceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public UserPreferenceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("code", 0, "message", "success", "data", read(user.userId()));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> update(@RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body == null ? Map.of() : body);
        } catch (Exception e) {
            json = "{}";
        }
        // 整体覆盖语义：调用方提交完整偏好对象（前端合并后提交）。
        // 用 UPDATE→INSERT 两步而非 ON CONFLICT：语义相同但可移植（H2/PG 均可，
        // 也便于用真实 DB 做单测），并避免 JDBC 侧 ::jsonb 方言差异。
        int updated = jdbc.update(
                "UPDATE user_preferences SET preferences = ?, updated_at = NOW() WHERE user_id = ?",
                json, user.userId());
        if (updated == 0) {
            jdbc.update("INSERT INTO user_preferences (user_id, preferences, updated_at) "
                    + "VALUES (?, ?, NOW())", user.userId(), json);
        }
        return Map.of("code", 0, "message", "success", "data", read(user.userId()));
    }

    private Map<String, Object> read(java.util.UUID userId) {
        try {
            // 用 query(RowMapper) 而非 queryForList(sql, Class, args)：
            // 后者在 Mockito 下受重载/泛型推断干扰，测试难以稳定打桩。
            var rows = jdbc.query(
                    "SELECT preferences::text FROM user_preferences WHERE user_id = ?",
                    (rs, i) -> rs.getString(1), userId);
            if (rows.isEmpty() || rows.get(0) == null) return Map.of();
            return MAPPER.readValue(rows.get(0), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
