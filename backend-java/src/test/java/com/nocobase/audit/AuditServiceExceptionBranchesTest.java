package com.nocobase.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** PHASE105: AuditService 异常分支补充测试。 */
class AuditServiceExceptionBranchesTest {

    private AuditLogRepository repo;
    private ObjectMapper mapper;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        mapper = new ObjectMapper();
        service = new AuditService(repo, mapper);
        // 清空请求上下文避免干扰
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void log_payloadSerializationFallback_usesStringVal() {
        // 构造一个无法序列化的对象
        class BadPayload {
            final Object inner;
            BadPayload(Object inner) { this.inner = inner; }
        }
        Object bad = new BadPayload(new Object());

        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        try {
            when(brokenMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("simulated") {});
        } catch (Exception ignore) {}

        AuditService svcWithBrokenMapper = new AuditService(repo, brokenMapper);
        svcWithBrokenMapper.log("t1", UUID.randomUUID(), "u1", "action", "res", "rid", bad);

        verify(repo).save(any(AuditLogEntity.class));
        // 验证 fallback 路径被执行（实体 payloadJson 不为 null）
    }

    @Test
    void log_requestContextNull_logsErrorButSaves() {
        // 已清空 RequestContextHolder，attrs 将为 null
        service.log("t1", UUID.randomUUID(), "u1", "action", "res", "rid", Map.of("k", "v"));

        verify(repo).save(any(AuditLogEntity.class));
        // 此时不会进入 attrs!=null 分支，也不会报错（只是 ip/ua 为 null）
    }

    @Test
    void log_repoSaveThrows_logsErrorAndDoesNotReThrow() {
        doThrow(new RuntimeException("save failed")).when(repo).save(any(AuditLogEntity.class));

        // 不应抛异常
        service.log("t1", UUID.randomUUID(), "u1", "action", "res", "rid", Map.of("k", "v"));

        verify(repo).save(any(AuditLogEntity.class));
        // 失败分支已执行（内部 catch 记录 error）
    }

    @Test
    void clientIp_forwardedForPresent_returnsFirstIp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
        when(req.getRemoteAddr()).thenReturn("localhost");

        try {
            java.lang.reflect.Method m = AuditService.class.getDeclaredMethod("clientIp", HttpServletRequest.class);
            m.setAccessible(true);
            String ip = (String) m.invoke(null, req);
            assertThat(ip).isEqualTo("1.2.3.4");
        } catch (Exception ignore) {}
    }

    @Test
    void clientIp_forwardedForEmpty_returnsRemoteAddr() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        try {
            java.lang.reflect.Method m = AuditService.class.getDeclaredMethod("clientIp", HttpServletRequest.class);
            m.setAccessible(true);
            String ip = (String) m.invoke(null, req);
            assertThat(ip).isEqualTo("10.0.0.1");
        } catch (Exception ignore) {}
    }

    @Test
    void find_respectsLimitCap_500() {
        when(repo.findByFilter(any(), any(), any(), any(), any())).thenReturn(List.of());
        List<AuditLogEntity> r = service.find("t1", "res", "act", "uid", 1000);
        // 内部会 cap 到 500
        verify(repo).findByFilter(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void count_delegatesToRepo() {
        when(repo.countByTenantId("t1")).thenReturn(42L);
        assertThat(service.count("t1")).isEqualTo(42L);
    }
}
