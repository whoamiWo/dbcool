package com.nocobase.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 审计服务 — 记录关键操作 + 查询历史. */
@Service
public class AuditService {

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
            } catch (Exception ignored) {}
            repo.save(e);
        } catch (Exception ex) {
            // 审计失败不能影响业务,只 log
            System.err.println("[AUDIT] log failed: " + ex.getMessage());
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
