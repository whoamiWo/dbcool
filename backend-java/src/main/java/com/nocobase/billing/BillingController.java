package com.nocobase.billing;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计费 REST 控制器 (Billing Controller).
 * 
 * <p>提供套餐查询、订阅管理、账单查询、支付处理等 RESTful 端点。</p>
 *
 * <p><b>租户隔离</b>：所有租户相关端点的 tenantId 一律取自
 * {@link TenantContext#requireTenantId()}（JWT 过滤器写入），路径/body 里的
 * tenantId 仅做一致性校验，不一致直接 403 —— 不接受"调用方自称是谁"。</p>
 *
 * <p><b>默认关闭</b>：仅在 {@code billing.enabled=true} 时装配，未开启时
 * {@code /api/billing/*} 全部 404（前端据此提示"计费能力未启用"）。</p>
 */
@ConditionalOnProperty(prefix = "billing", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final BillingService billingService;
    private final AuditService auditService;

    public BillingController(BillingService billingService, AuditService auditService) {
        this.billingService = billingService;
        this.auditService = auditService;
    }

    // --- 套餐（全局只读） ---

    /** GET /api/billing/plans - 获取所有可用套餐。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/plans")
    public ResponseEntity<List<PlanTierEntity>> listPlans() {
        return ResponseEntity.ok(billingService.listAvailablePlans());
    }

    /** GET /api/billing/plans/{id} - 获取指定套餐。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/plans/{id}")
    public ResponseEntity<PlanTierEntity> getPlan(@PathVariable String id) {
        return billingService.getPlanById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** GET /api/billing/payment-providers - 已配置支付网关的支付方式。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/payment-providers")
    public ResponseEntity<Set<String>> listPaymentProviders() {
        return ResponseEntity.ok(billingService.supportedPaymentProviders());
    }

    // --- 订阅 ---

    /** GET /api/billing/subscription/{tenantId} - 获取租户当前订阅。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/subscription/{tenantId}")
    public ResponseEntity<SubscriptionEntity> getActiveSubscription(@PathVariable String tenantId) {
        return handle(() -> billingService.getActiveSubscription(requireTenant(tenantId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /** GET /api/billing/subscriptions/{tenantId} - 获取租户所有订阅历史。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/subscriptions/{tenantId}")
    public ResponseEntity<List<SubscriptionEntity>> getSubscriptions(@PathVariable String tenantId) {
        return handle(() -> ResponseEntity.ok(billingService.getSubscriptions(requireTenant(tenantId))));
    }

    /** POST /api/billing/subscription - 创建新订阅（租户取自上下文，忽略 body 中的 tenantId）。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/subscription")
    public ResponseEntity<SubscriptionEntity> createSubscription(
            @RequestBody CreateSubscriptionRequest request) {
        return handle(() -> {
            String tenantId = requireTenant(request == null ? null : request.tenantId());
            SubscriptionEntity sub = billingService.createSubscription(
                    tenantId, request.planTierId(), request.paymentMethod(), request.trial());
            auditService.log(tenantId, null, null, "BILLING_CREATE_SUBSCRIPTION", "billing/subscription",
                    sub.getId(), Map.of("planTierId", String.valueOf(request.planTierId()),
                            "trial", String.valueOf(request.trial())));
            return ResponseEntity.ok(sub);
        });
    }

    /** POST /api/billing/subscription/{id}/upgrade - 升级/降级套餐。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/subscription/{id}/upgrade")
    public ResponseEntity<SubscriptionEntity> upgradeSubscription(
            @PathVariable String id, @RequestBody UpgradeSubscriptionRequest request) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            SubscriptionEntity sub = billingService.upgradeSubscription(id, tenantId, request.planTierId());
            auditService.log(tenantId, null, null, "BILLING_UPGRADE_SUBSCRIPTION", "billing/subscription/upgrade",
                    id, Map.of("planTierId", String.valueOf(request.planTierId())));
            return ResponseEntity.ok(sub);
        });
    }

    /** POST /api/billing/subscription/{id}/cancel - 取消订阅 (周期末生效)。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/subscription/{id}/cancel")
    public ResponseEntity<SubscriptionEntity> cancelSubscription(@PathVariable String id) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            SubscriptionEntity sub = billingService.cancelSubscriptionAtPeriodEnd(id, tenantId);
            auditService.log(tenantId, null, null, "BILLING_CANCEL_SUBSCRIPTION", "billing/subscription/cancel",
                    id, Map.of("mode", "at_period_end"));
            return ResponseEntity.ok(sub);
        });
    }

    /** POST /api/billing/subscription/{id}/cancel-immediate - 立即取消订阅。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/subscription/{id}/cancel-immediate")
    public ResponseEntity<SubscriptionEntity> cancelSubscriptionImmediately(@PathVariable String id) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            SubscriptionEntity sub = billingService.cancelSubscriptionImmediately(id, tenantId);
            auditService.log(tenantId, null, null, "BILLING_CANCEL_SUBSCRIPTION", "billing/subscription/cancel-immediate",
                    id, Map.of("mode", "immediate"));
            return ResponseEntity.ok(sub);
        });
    }

    /** POST /api/billing/subscription/{id}/renew - 续订订阅。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/subscription/{id}/renew")
    public ResponseEntity<SubscriptionEntity> renewSubscription(@PathVariable String id) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            SubscriptionEntity sub = billingService.renewSubscription(id, tenantId);
            auditService.log(tenantId, null, null, "BILLING_RENEW_SUBSCRIPTION", "billing/subscription/renew",
                    id, Map.of("periodEnd", String.valueOf(sub.getCurrentPeriodEnd())));
            return ResponseEntity.ok(sub);
        });
    }

    // --- 账单 ---

    /** GET /api/billing/invoices/{tenantId} - 获取租户账单列表。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/invoices/{tenantId}")
    public ResponseEntity<List<InvoiceEntity>> getInvoices(@PathVariable String tenantId) {
        return handle(() -> ResponseEntity.ok(billingService.getInvoices(requireTenant(tenantId))));
    }

    /** GET /api/billing/invoices/{tenantId}/unpaid - 获取未支付账单。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/invoices/{tenantId}/unpaid")
    public ResponseEntity<List<InvoiceEntity>> getUnpaidInvoices(@PathVariable String tenantId) {
        return handle(() -> ResponseEntity.ok(billingService.getUnpaidInvoices(requireTenant(tenantId))));
    }

    /** POST /api/billing/invoices/{id}/pay - 标记账单已支付。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/invoices/{id}/pay")
    public ResponseEntity<InvoiceEntity> markInvoicePaid(@PathVariable String id) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            InvoiceEntity invoice = billingService.markInvoiceAsPaid(id, tenantId, Instant.now());
            auditService.log(tenantId, null, null, "BILLING_PAY_INVOICE", "billing/invoice/pay",
                    id, Map.of("invoiceNumber", String.valueOf(invoice.getInvoiceNumber())));
            return ResponseEntity.ok(invoice);
        });
    }

    // --- 支付 ---

    /** POST /api/billing/payments - 发起支付（真实调用支付网关）。 */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/payments")
    public ResponseEntity<PaymentEntity> recordPayment(@RequestBody RecordPaymentRequest request) {
        return handle(() -> {
            String tenantId = requireTenant(request == null ? null : request.tenantId());
            PaymentEntity payment = billingService.recordPayment(
                    tenantId, request.invoiceId(), request.subscriptionId(),
                    request.amount(), request.paymentMethod(), request.transactionId());
            auditService.log(tenantId, null, null, "BILLING_RECORD_PAYMENT", "billing/payment",
                    payment.getId(), Map.of("status", String.valueOf(payment.getStatus()),
                            "amount", String.valueOf(request.amount()),
                            "paymentMethod", String.valueOf(request.paymentMethod())));
            return ResponseEntity.ok(payment);
        });
    }

    /** POST /api/billing/payments/{id}/confirm - 人工确认 PENDING 支付成功。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/payments/{id}/confirm")
    public ResponseEntity<PaymentEntity> confirmPayment(@PathVariable String id) {
        String tenantId = requireTenant(null);
        return handle(() -> {
            PaymentEntity payment = billingService.confirmPayment(id, tenantId);
            auditService.log(tenantId, null, null, "BILLING_CONFIRM_PAYMENT", "billing/payment/confirm",
                    id, Map.of("status", String.valueOf(payment.getStatus())));
            return ResponseEntity.ok(payment);
        });
    }

    /** GET /api/billing/payments/{tenantId} - 获取租户支付记录。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/payments/{tenantId}")
    public ResponseEntity<List<PaymentEntity>> getPayments(@PathVariable String tenantId) {
        return handle(() -> ResponseEntity.ok(billingService.getPayments(requireTenant(tenantId))));
    }

    // --- 运维 ---

    /** POST /api/billing/scan-overdue - 扫描逾期订阅和账单 (定时任务也可手动触发)。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/scan-overdue")
    public ResponseEntity<Map<String, Object>> scanOverdue() {
        return handle(() -> {
            int expired = billingService.scanOverdueSubscriptions();
            int uncollectible = billingService.scanOverdueInvoices();
            String tenantId = TenantContext.currentTenantId();
            auditService.log(tenantId == null ? "system" : tenantId, null, null,
                    "BILLING_SCAN_OVERDUE", "billing/scan-overdue", null,
                    Map.of("expiredSubscriptions", expired, "uncollectibleInvoices", uncollectible));
            return ResponseEntity.ok(Map.of("status", "ok",
                    "expiredSubscriptions", expired, "uncollectibleInvoices", uncollectible));
        });
    }

    // --- 租户与错误处理 ---

    /**
     * 取当前租户并校验与请求中声明的 tenantId 一致。
     *
     * @param declaredTenantId 路径/body 中声明的租户，可为 null 表示未声明
     */
    private String requireTenant(String declaredTenantId) {
        String tenantId;
        try {
            tenantId = TenantContext.requireTenantId();
        } catch (IllegalStateException e) {
            // 请求未过 JwtAuthFilter / 上下文未设置 —— 属于拒绝访问，不是业务参数错误
            throw new BillingAccessDeniedException("租户上下文缺失: " + e.getMessage());
        }
        if (declaredTenantId != null && !declaredTenantId.isBlank()
                && !tenantId.equals(declaredTenantId)) {
            throw new BillingAccessDeniedException(
                    "禁止访问其他租户的计费数据 (current=" + tenantId + ", requested=" + declaredTenantId + ")");
        }
        return tenantId;
    }

    /** 统一异常映射：跨租户 403，业务参数 400，其他业务异常 400 并带原因。 */
    private <T> ResponseEntity<T> handle(java.util.function.Supplier<ResponseEntity<T>> supplier) {
        try {
            return supplier.get();
        } catch (BillingAccessDeniedException e) {
            log.warn("Billing access denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (UnsupportedPaymentProviderException e) {
            log.warn("Unsupported payment provider: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Billing request failed", e);
            return ResponseEntity.badRequest().build();
        }
    }

    // --- Request DTOs ---

    public record CreateSubscriptionRequest(
        String tenantId,
        String planTierId,
        String paymentMethod,
        boolean trial
    ) {}

    public record UpgradeSubscriptionRequest(String planTierId) {}

    public record RecordPaymentRequest(
        String tenantId,
        String invoiceId,
        String subscriptionId,
        BigDecimal amount,
        String paymentMethod,
        String transactionId
    ) {}
}
