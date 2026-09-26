package com.nocobase.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 审计服务 — 记录关键操作 + 查询历史. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** 异步写入失败也不阻塞主流程. */
    public void log(String tenantId, Object userId, String username,
                    String action, String resource, String resourceId,
                    Object payload) {
        try {
            AuditLogEntity e = new AuditLogEntity();
            e.setId(UUID.randomUUID());
            e.setTenantId(tenantId == null ? "unknown" : tenantId);
            e.setUserId(userId == null ? "anonymous" : userId.toString());
            e.setUsername(username);
            e.setAction(action);
            e.setResource(resource);
            e.setResourceId(resourceId);
            e.setCreatedAt(Instant.now());
            if (payload != null) {
                try {
                    e.setPayloadJson(mapper.writeValueAsString(payload));
                } catch (JsonProcessingException ignored) {
                    e.setPayloadJson(String.valueOf(payload));
                }
            }
            // 捕获 IP / User-Agent(尽力而为)
            try {
                ServletRequestAttributes attrs = (ServletRequestAttributes)
                        RequestContextHolder.getRequestAttributes();
                if (attrs != null) {
                    HttpServletRequest req = attrs.getRequest();
                    e.setIp(clientIp(req));
                    String ua = req.getHeader("User-Agent");
                    e.setUserAgent(ua == null ? null : (ua.length() > 250 ? ua.substring(0, 250) : ua));
                }
            } catch (Exception ctxEx) {
                // Stage 1 安全收口:审计上下文提取失败必须 ERROR 告警,禁静默吞异常。
                // 吞异常会导致审计日志缺失 — 安全事件无法溯源。
                log.error("[audit] 请求上下文提取失败(审计记录可能缺失 IP/UA): {}",
                        ctxEx.getMessage(), ctxEx);
            }
            repo.save(e);
        } catch (Exception ex) {
            // 审计失败不能影响业务主流程,但必须 ERROR 告警(禁 System.err/吞异常)。
            log.error("[audit] log failed: {}", ex.getMessage(), ex);
        }
    }

    public List<AuditLogEntity> find(String tenantId, String resource, String action,
                                     String userId, int limit) {
        return repo.findByFilter(tenantId, resource, action, userId,
                PageRequest.of(0, Math.min(limit, 500)));
    }

    public long count(String tenantId) {
        return repo.countByTenantId(tenantId);
    }

    private static String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
