package com.nocobase.acl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ROW-level ACL 评估器(Week 14.5).
 *
 * <p>语义:有任一 policy 命中 = 允许(OR);无 policy = 不受限(放行);显式 deny = 拒绝(预留).
 *
 * <p>表达式语法:
 * <pre>{@code { "field": "owner_id", "op": "eq", "value": "$currentUser" }}</pre>
 *
 * <p>op 支持:eq / neq / in / is_null / not_null / contains
 * value 支持占位符:$currentUser / $currentRoles(数组)/ 普通值
 */
@Service
public class RowAclService {

    private static final Logger log = LoggerFactory.getLogger(RowAclService.class);

    private final AclRowPolicyRepository repository;
    private final ObjectMapper objectMapper;

    public RowAclService(AclRowPolicyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单条记录评估 read 访问(principal 可见该记录 ?).
     *
     * @return true 允许 / false 拒绝
     */
    public boolean evaluateRead(String tenantId,
                                String collection,
                                Map<String, Object> record,
                                Principal principal) {
        return evaluate(tenantId, collection, "read", record, principal);
    }

    /**
     * 对单条记录评估 update 访问(Week 14.5 P3-3 补完).
     *
     * <p>默认行为:read policy 通过 ⇒ update 允许.
     * 若有 update-specific policy,则用 update 求值.
     */
    public boolean evaluateUpdate(String tenantId,
                                   String collection,
                                   Map<String, Object> record,
                                   Principal principal) {
        // 优先看 update policy
        if (hasActionPolicies(tenantId, collection, "update")) {
            return evaluate(tenantId, collection, "update", record, principal);
        }
        // 否则用 read policy 兜底(可见即可改 — 常见 ACL 习惯)
        return evaluateRead(tenantId, collection, record, principal);
    }

    /**
     * 对单条记录评估 delete 访问(Week 14.5 P3-3 补完).
     */
    public boolean evaluateDelete(String tenantId,
                                   String collection,
                                   Map<String, Object> record,
                                   Principal principal) {
        if (hasActionPolicies(tenantId, collection, "delete")) {
            return evaluate(tenantId, collection, "delete", record, principal);
        }
        return evaluateRead(tenantId, collection, record, principal);
    }

    private boolean hasActionPolicies(String tenantId, String collection, String action) {
        return !repository.findApplicable(tenantId, collection, action).isEmpty();
    }

    /**
     * 过滤可读记录(用于 list API 末尾过滤).
     */
    public List<Map<String, Object>> filterReadable(String tenantId,
                                                   String collection,
                                                   Collection<Map<String, Object>> records,
                                                   Principal principal) {
        List<AclRowPolicyEntity> policies = repository.findApplicable(
                tenantId, collection, "read");
        log.debug("RowAcl filter collection={} principal={} policies={} records={}",
                collection, principal, policies.size(), records.size());
        if (policies.isEmpty()) {
            return new ArrayList<>(records); // 无策略 = 放行
        }
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (Map<String, Object> r : records) {
            if (matchesAny(policies, r, principal)) {
                out.add(r);
            }
        }
        log.debug("RowAcl filter {}: {} -> {} records",
                collection, records.size(), out.size());
        return out;
    }

    /**
     * 内部统一评估入口.
     */
    private boolean evaluate(String tenantId,
                             String collection,
                             String action,
                             Map<String, Object> record,
                             Principal principal) {
        if (principal == null || principal.userId() == null) {
            return false; // 未登录一律拒绝(由 Security 兜底)
        }
        List<AclRowPolicyEntity> policies = repository.findApplicable(
                tenantId, collection, action);
        if (policies.isEmpty()) {
            // Stage 1 安全收口:无行级 policy 时 fail-closed(默认拒绝)。
            // 原「无 policy = 放行」语义存在越权风险。
            log.warn("[row-acl] 无策略放行已关闭:tenant={} collection={} action={} — 拒绝请求",
                    tenantId, collection, action);
            return false; // 无 policy = 拒绝
        }
        return matchesAny(policies, record, principal);
    }

    private boolean matchesAny(List<AclRowPolicyEntity> policies,
                               Map<String, Object> record,
                               Principal principal) {
        // 先收集所有适用(principal 命中)的策略
        List<AclRowPolicyEntity> applicable = new ArrayList<>();
        for (AclRowPolicyEntity p : policies) {
            if (appliesTo(p, principal)) applicable.add(p);
        }
        // 没有任何适用策略 → 放行(无约束)
        if (applicable.isEmpty()) {
            return true;
        }
        // 适用策略任一命中 → 通过
        for (AclRowPolicyEntity p : applicable) {
            try {
                if (evaluateExpression(p.getExpression(), record, principal)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("RowAcl 策略 {} 求值失败: {}", p.getId(), e.getMessage());
            }
        }
        return false;
    }

    /** principal 是否命中策略的适用对象(user-id / role). */
    private boolean appliesTo(AclRowPolicyEntity p, Principal principal) {
        return switch (p.getPrincipalType()) {
            case "user" -> Objects.equals(p.getPrincipalId(), principal.userId());
            case "role" -> principal.roleNames() != null
                    && principal.roleNames().contains(p.getPrincipalId());
            default -> false;
        };
    }

    /** 求值单条策略的 expression. */
    private boolean evaluateExpression(String json,
                                       Map<String, Object> record,
                                       Principal principal) throws Exception {
        Map<String, Object> expr = objectMapper.readValue(json, new TypeReference<>() {});
        String field = (String) expr.get("field");
        String op = (String) expr.get("op");
        Object value = resolveValue(expr.get("value"), principal);

        Object actual = record == null ? null : record.get(field);

        return switch (op) {
            case "eq"        -> Objects.equals(actual, value);
            case "neq"       -> !Objects.equals(actual, value);
            case "in"        -> actual != null && value instanceof Collection<?> c && c.contains(actual);
            case "is_null"   -> actual == null;
            case "not_null"  -> actual != null;
            case "contains"  -> actual != null && actual.toString().contains(String.valueOf(value));
            default -> false;
        };
    }

    /** 占位符替换. */
    private Object resolveValue(Object raw, Principal principal) {
        if (raw instanceof String s) {
            return switch (s) {
                case "$currentUser"  -> principal.userId();
                case "$currentRoles" -> principal.roleNames() == null
                        ? Collections.emptySet() : Set.copyOf(principal.roleNames());
                default -> s;
            };
        }
        return raw;
    }

    /** 主体(principal) — controller 层注入. */
    public record Principal(String userId, List<String> roleNames) {
        public Principal {
            if (roleNames == null) roleNames = List.of();
        }
    }
}