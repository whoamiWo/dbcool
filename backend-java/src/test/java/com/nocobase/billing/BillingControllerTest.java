package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * B4 计费控制器单测：重点验证**租户隔离**与支付失败不外泄为成功。
 *
 * <p>租户一律取自 {@link TenantContext}（JWT 过滤器写入），路径/body 中的 tenantId
 * 只做一致性校验，不一致必须 403。</p>
 */
class BillingControllerTest {

    private BillingService billingService;
    private AuditService auditService;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        auditService = mock(AuditService.class);
        controller = new BillingController(billingService, auditService);
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- 套餐（全局） ---

    @Test
    @DisplayName("GET /plans 返回套餐列表")
    void listPlans() {
        when(billingService.listAvailablePlans()).thenReturn(List.of(plan("plan_pro")));

        ResponseEntity<List<PlanTierEntity>> resp = controller.listPlans();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("GET /plans/{id} 不存在 → 404")
    void getPlan_notFound() {
        when(billingService.getPlanById("nope")).thenReturn(Optional.empty());

        assertThat(controller.getPlan("nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /payment-providers 返回已注册渠道")
    void listPaymentProviders() {
        when(billingService.supportedPaymentProviders()).thenReturn(Set.of("manual", "alipay"));

        assertThat(controller.listPaymentProviders().getBody()).containsExactlyInAnyOrder("manual", "alipay");
    }

    // --- 订阅 ---

    @Test
    @DisplayName("GET /subscription/{tenantId} 跨租户 → 403，且不得查询 Service")
    void getActiveSubscription_crossTenant_forbidden() {
        ResponseEntity<SubscriptionEntity> resp = controller.getActiveSubscription("t2");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(billingService, never()).getActiveSubscription(anyString());
    }

    @Test
    @DisplayName("GET /subscription/{tenantId} 同租户 → 正常查询")
    void getActiveSubscription_sameTenant_ok() {
        when(billingService.getActiveSubscription("t1")).thenReturn(Optional.of(subscription("sub-1")));

        ResponseEntity<SubscriptionEntity> resp = controller.getActiveSubscription("t1");

        assertThat(resp.getBody().getId()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("GET /subscriptions/{tenantId} 跨租户 → 403")
    void getSubscriptions_crossTenant_forbidden() {
        assertThat(controller.getSubscriptions("t2").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /subscription body tenantId 与上下文一致 → 用上下文租户创建")
    void createSubscription_usesContextTenant() {
        when(billingService.createSubscription(eq("t1"), eq("plan_pro"), eq("manual"), eq(false)))
                .thenReturn(subscription("sub-1"));

        ResponseEntity<SubscriptionEntity> resp = controller.createSubscription(
                new BillingController.CreateSubscriptionRequest("t1", "plan_pro", "manual", false));

        assertThat(resp.getBody().getId()).isEqualTo("sub-1");
        verify(billingService).createSubscription("t1", "plan_pro", "manual", false);
        verify(auditService).log(eq("t1"), any(), any(), eq("BILLING_CREATE_SUBSCRIPTION"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST /subscription body tenantId 与上下文不一致 → 403")
    void createSubscription_tenantMismatch_forbidden() {
        ResponseEntity<SubscriptionEntity> resp = controller.createSubscription(
                new BillingController.CreateSubscriptionRequest("t2", "plan_pro", "manual", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(billingService, never()).createSubscription(anyString(), anyString(), anyString(), eq(false));
    }

    @Test
    @DisplayName("POST /subscription 业务异常 → 400")
    void createSubscription_businessError_returns400() {
        when(billingService.createSubscription(anyString(), anyString(), anyString(), eq(false)))
                .thenThrow(new IllegalStateException("租户已存在生效中的订阅"));

        ResponseEntity<SubscriptionEntity> resp = controller.createSubscription(
                new BillingController.CreateSubscriptionRequest("t1", "plan_pro", "manual", false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /subscription/{id}/upgrade 走归属校验后的 Service")
    void upgradeSubscription() {
        when(billingService.upgradeSubscription("sub-1", "t1", "plan_pro")).thenReturn(subscription("sub-1"));

        ResponseEntity<SubscriptionEntity> resp = controller.upgradeSubscription("sub-1",
                new BillingController.UpgradeSubscriptionRequest("plan_pro"));

        assertThat(resp.getBody().getId()).isEqualTo("sub-1");
        verify(auditService).log(eq("t1"), any(), any(), eq("BILLING_UPGRADE_SUBSCRIPTION"), anyString(), eq("sub-1"), any());
    }

    @Test
    @DisplayName("取消/立即取消/续订：均传上下文租户并留痕")
    void cancelAndRenew() {
        when(billingService.cancelSubscriptionAtPeriodEnd("sub-1", "t1")).thenReturn(subscription("sub-1"));
        when(billingService.cancelSubscriptionImmediately("sub-1", "t1")).thenReturn(subscription("sub-1"));
        when(billingService.renewSubscription("sub-1", "t1")).thenReturn(subscription("sub-1"));

        controller.cancelSubscription("sub-1");
        controller.cancelSubscriptionImmediately("sub-1");
        controller.renewSubscription("sub-1");

        verify(billingService).cancelSubscriptionAtPeriodEnd("sub-1", "t1");
        verify(billingService).cancelSubscriptionImmediately("sub-1", "t1");
        verify(billingService).renewSubscription("sub-1", "t1");
    }

    // --- 账单 ---

    @Test
    @DisplayName("GET /invoices/{tenantId} 跨租户 → 403")
    void getInvoices_crossTenant_forbidden() {
        assertThat(controller.getInvoices("t2").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /invoices/{tenantId}/unpaid 同租户 → 正常")
    void getUnpaidInvoices_ok() {
        when(billingService.getUnpaidInvoices("t1")).thenReturn(List.of());

        assertThat(controller.getUnpaidInvoices("t1").getBody()).isEmpty();
    }

    @Test
    @DisplayName("POST /invoices/{id}/pay 留痕")
    void markInvoicePaid() {
        when(billingService.markInvoiceAsPaid(eq("inv-1"), eq("t1"), any(Instant.class)))
                .thenReturn(invoice("inv-1"));

        ResponseEntity<InvoiceEntity> resp = controller.markInvoicePaid("inv-1");

        assertThat(resp.getBody().getId()).isEqualTo("inv-1");
        verify(auditService).log(eq("t1"), any(), any(), eq("BILLING_PAY_INVOICE"), anyString(), eq("inv-1"), any());
    }

    // --- 支付 ---

    @Test
    @DisplayName("POST /payments 网关成功 → 返回支付记录并留痕")
    void recordPayment_success() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId("pay-1");
        payment.setStatus(PaymentEntity.Status.SUCCEEDED);
        payment.setTransactionId("tx-1");
        when(billingService.recordPayment(eq("t1"), eq("inv-1"), any(), eq(new BigDecimal("99.00")), eq("alipay"), any()))
                .thenReturn(payment);

        ResponseEntity<PaymentEntity> resp = controller.recordPayment(
                new BillingController.RecordPaymentRequest("t1", "inv-1", null, new BigDecimal("99.00"), "alipay", null));

        assertThat(resp.getBody().getStatus()).isEqualTo(PaymentEntity.Status.SUCCEEDED);
        verify(auditService).log(eq("t1"), any(), any(), eq("BILLING_RECORD_PAYMENT"), anyString(), eq("pay-1"), any());
    }

    @Test
    @DisplayName("POST /payments 未注册支付方式 → 400，且不落库")
    void recordPayment_unsupportedProvider_returns400() {
        when(billingService.recordPayment(anyString(), any(), any(), any(), eq("bitcoin"), any()))
                .thenThrow(new UnsupportedPaymentProviderException("bitcoin", Set.of("manual")));

        ResponseEntity<PaymentEntity> resp = controller.recordPayment(
                new BillingController.RecordPaymentRequest("t1", "inv-1", null, BigDecimal.ONE, "bitcoin", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(auditService, never()).log(any(), any(), any(), eq("BILLING_RECORD_PAYMENT"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("POST /payments 跨租户 → 403")
    void recordPayment_crossTenant_forbidden() {
        ResponseEntity<PaymentEntity> resp = controller.recordPayment(
                new BillingController.RecordPaymentRequest("t9", "inv-1", null, BigDecimal.ONE, "alipay", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /payments/{id}/confirm 人工确认")
    void confirmPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId("pay-1");
        payment.setStatus(PaymentEntity.Status.SUCCEEDED);
        when(billingService.confirmPayment("pay-1", "t1")).thenReturn(payment);

        assertThat(controller.confirmPayment("pay-1").getBody().getStatus())
                .isEqualTo(PaymentEntity.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("GET /payments/{tenantId} 跨租户 → 403")
    void getPayments_crossTenant_forbidden() {
        assertThat(controller.getPayments("t2").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- 运维 ---

    @Test
    @DisplayName("POST /scan-overdue 返回扫描统计")
    void scanOverdue() {
        when(billingService.scanOverdueSubscriptions()).thenReturn(2);
        when(billingService.scanOverdueInvoices()).thenReturn(3);

        ResponseEntity<java.util.Map<String, Object>> resp = controller.scanOverdue();

        assertThat(resp.getBody()).containsEntry("expiredSubscriptions", 2)
                .containsEntry("uncollectibleInvoices", 3);
        verify(auditService).log(eq("t1"), any(), any(), eq("BILLING_SCAN_OVERDUE"), anyString(), any(), any());
    }

    // --- 租户上下文 ---

    @Test
    @DisplayName("租户上下文缺失 → 403，不允许无租户访问计费数据")
    void missingTenantContext_forbidden() {
        TenantContext.clear();

        assertThat(controller.getInvoices("t1").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(billingService, never()).getInvoices(anyString());
    }

    private static PlanTierEntity plan(String id) {
        PlanTierEntity p = new PlanTierEntity();
        p.setId(id);
        p.setName("专业版");
        return p;
    }

    private static SubscriptionEntity subscription(String id) {
        SubscriptionEntity s = new SubscriptionEntity();
        s.setId(id);
        s.setTenantId("t1");
        s.setStatus(SubscriptionEntity.Status.ACTIVE);
        s.setCurrentPeriodStart(LocalDate.now());
        s.setCurrentPeriodEnd(LocalDate.now().plusMonths(1));
        return s;
    }

    private static InvoiceEntity invoice(String id) {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setId(id);
        inv.setTenantId("t1");
        inv.setInvoiceNumber("INV-1");
        return inv;
    }
}
