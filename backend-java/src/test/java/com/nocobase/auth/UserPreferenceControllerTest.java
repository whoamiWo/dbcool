package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * E3：通知偏好读写。用真实 H2（JSONB 域映射为 CLOB）而不是 mock ——
 * 避免再出现"SQL 写错但 mock 全绿"。
 */
class UserPreferenceControllerTest {

    private JdbcTemplate jdbc;
    private UserPreferenceController controller;
    private final AuthenticatedUser user =
            new AuthenticatedUser(UUID.randomUUID(), "alice", "t1");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:prefs_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                        + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS CLOB\\;",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_preferences ("
                + "user_id UUID PRIMARY KEY, "
                + "preferences JSONB NOT NULL DEFAULT '{}'::jsonb, "
                + "updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW())");
        controller = new UserPreferenceController(jdbc);
    }

    @Test
    @DisplayName("GET：无记录 → 空对象")
    void get_empty() {
        assertThat(controller.get(user).get("data")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("PUT 后 GET：真实 JSONB 往返")
    void put_thenGet_roundTrip() {
        Map<String, Object> body = Map.of(
                "notify", Map.of("quietHours", List.of("22:00", "08:00"),
                        "APPROVAL_TODO", List.of()));

        controller.update(body, user);
        Map<String, Object> r = controller.get(user);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> notify = (Map<String, Object>) data.get("notify");
        assertThat(notify).containsEntry("quietHours", List.of("22:00", "08:00"));
    }

    @Test
    @DisplayName("PUT 两次：第二次走 UPDATE，不产生主键冲突")
    void put_twice_updates() {
        controller.update(Map.of("notify", Map.of("QUOTA", List.of())), user);
        controller.update(Map.of("notify", Map.of("QUOTA", List.of("EMAIL"))), user);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.get(user).get("data");
        assertThat(data).containsKey("notify");
    }
}
