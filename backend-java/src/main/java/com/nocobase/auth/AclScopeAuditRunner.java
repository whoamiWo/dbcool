package com.nocobase.auth;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * R8：启动时汇总库中已有的 CUSTOM 数据范围策略。
 *
 * <p>CUSTOM 当前没有实现（也不允许再写入，见 {@code RoleAclController}），
 * 存量策略会被 fail-closed 拒绝 —— 必须在日志中显式告警，避免"列表/详情全空且无提示"。
 * 管理端可通过 {@code GET /api/admin/acl/unsupported} 查看同一清单。
 */
@Component
public class AclScopeAuditRunner {

    private static final Logger log = LoggerFactory.getLogger(AclScopeAuditRunner.class);

    private final AclPolicyRepository policyRepository;

    public AclScopeAuditRunner(AclPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void auditCustomScopes() {
        try {
            List<AclPolicyEntity> custom = policyRepository.findAll().stream()
                    .filter(p -> p.getDataScopeType() == AclPolicyEntity.DataScopeType.CUSTOM)
                    .toList();
            if (custom.isEmpty()) return;
            Map<String, Long> byTenant = custom.stream()
                    .collect(Collectors.groupingBy(
                            p -> p.getTenantId() == null ? "unknown" : p.getTenantId(),
                            Collectors.counting()));
            log.warn("[acl] 检测到 {} 条 CUSTOM 数据范围策略（暂不支持，将按拒绝处理）: {}",
                    custom.size(), byTenant);
            for (AclPolicyEntity p : custom) {
                log.warn("[acl] unsupported CUSTOM scope: tenant={} roleId={} subject={} policy={}",
                        p.getTenantId(), p.getRoleId(), p.getSubject(), p.getId());
            }
        } catch (Exception e) {
            // 启动审计失败不得阻断服务；但需可观测
            log.warn("[acl] CUSTOM 数据范围审计失败: {}", e.getMessage());
        }
    }
}
