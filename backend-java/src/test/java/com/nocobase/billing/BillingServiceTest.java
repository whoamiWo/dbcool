package com.nocobase.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** B4 计费服务单测：默认套餐初始化、订阅归属校验、支付网关三态、逾期扫描。 */
class BillingServiceTest {

    private PlanTierRepository planTierRepository;
    private SubscriptionRepository subscriptionRepository;
    private InvoiceRepository invoiceRepository;
    private PaymentRepository paymentRepository;
    private PaymentGatewayRegistry paymentGatewayRegistry;
    private TenantRepository tenantRepository;
    private BillingService service;

    @BeforeEach
    void setUp() {
        planTierRepository = mock(PlanTierRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentGatewayRegistry = mock(PaymentGatewayRegistry.class);
        tenantRepository = mock(TenantRepository.class);
        service = new BillingService(planTierRepository, subscriptionRepository, invoiceRepository,
                paymentRepository, paymentGatewayRegistry, tenantRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("构造器注入：仓储为 null 立即失败，不允许静默降级")
    void constructor_rejectsNullRepository() {
        assertThatThrownBy(() -> new BillingService(null, subscriptionRepository, invoiceRepository,
                paymentRepository, paymentGatewayRegistry, tenantRepository, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("planTierRepository");
    }

    @Test
    @DisplayName("initDefaultPlanTiers：无套餐时播种 3 个默认套餐")
    void initDefaultPlanTiers_seedsWhenEmpty() {
        when(planTierRepository.count()).thenReturn(0L);

        service.initDefaultPlanTiers();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlanTierEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(planTierRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(PlanTierEntity::getSlug)
                .containsExactly("free", "pro", "enterprise");
    }

    @Test
    @DisplayName("initDefaultPlanTiers：已有套餐时不重复播种")
    void initDefaultPlanTiers_skipsWhenExisting() {
        when(planTierRepository.count()).thenReturn(3L);

        service.initDefaultPlanTiers();

        verify(planTierRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("createSubscription：已存在生效订阅时拒绝创建（对齐部分唯一索引）")
    void createSubscription_rejectsDuplicateActive() {
        SubscriptionEntity existing = new SubscriptionEntity();
        existing.setId("sub-1");
        existing.setTenantId("t1");
        when(subscriptionRepository.findByTenantIdAndStatusIn(eq("t1"), anyList()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createSubscription("t1", "plan_pro", "alipay", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在生效中的订阅");
    }

    @Test
    @DisplayName("createSubscription：按月下发周期并落 ACTIVE")
    void createSubscription_setsPeriodAndStatus() {
        when(subscriptionRepository.findByTenantIdAndStatusIn(eq("t1"), anyList()))
                .thenReturn(Optional.empty());
        when(planTierRepository.findById("plan_pro")).thenReturn(Optional.of(plan("plan_pro", "pro")));
        when(subscriptionRepository.save(any(SubscriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity saved = service.createSubscription("t1", "plan_pro", "alipay", false);

        assertThat(saved.getStatus()).isEqualTo(SubscriptionEntity.Status.ACTIVE);
        assertThat(saved.getCurrentPeriodEnd()).isEqualTo(LocalDate.now().plusMonths(1));
        assertThat(saved.getTenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("upgradeSubscription：跨租户订阅直接拒绝")
    void upgradeSubscription_crossTenant_denied() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("other-tenant");
        when(subscriptionRepository.findById("sub-1")).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.upgradeSubscription("sub-1", "t1", "plan_pro"))
                .isInstanceOf(BillingAccessDeniedException.class);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("markInvoiceAsPaid：跨租户账单直接拒绝")
    void markInvoiceAsPaid_crossTenant_denied() {
        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId("inv-1");
        invoice.setTenantId("other-tenant");
        when(invoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.markInvoiceAsPaid("inv-1", "t1", java.time.Instant.now()))
                .isInstanceOf(BillingAccessDeniedException.class);
    }

    @Test
    @DisplayName("recordPayment：网关返回成功 → 支付 SUCCEEDED 且账单置 PAID")
    void recordPayment_success_marksInvoicePaid() {
        InvoiceEntity invoice = invoice("inv-1", "t1");
        when(invoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice));
        when(subscriptionRepository.findByTenantIdAndStatusIn(eq("t1"), anyList()))
                .thenReturn(Optional.empty());
        when(paymentGatewayRegistry.resolve("alipay"))
                .thenReturn(new FixedGateway(PaymentGateway.PaymentResult.succeeded("tx-1", "{\"status\":\"succeeded\"}")));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.save(any(InvoiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentEntity payment = service.recordPayment("t1", "inv-1", "sub-1",
                new BigDecimal("99.00"), "alipay", null);

        assertThat(payment.getStatus()).isEqualTo(PaymentEntity.Status.SUCCEEDED);
        assertThat(payment.getTransactionId()).isEqualTo("tx-1");
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceEntity.Status.PAID);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    @DisplayName("recordPayment：网关返回失败 → 支付 FAILED，账单不得置 PAID")
    void recordPayment_failed_keepsInvoiceOpen() {
        InvoiceEntity invoice = invoice("inv-1", "t1");
        when(invoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice));
        when(paymentGatewayRegistry.resolve("alipay"))
                .thenReturn(new FixedGateway(PaymentGateway.PaymentResult.failed("余额不足", null)));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentEntity payment = service.recordPayment("t1", "inv-1", "sub-1",
                new BigDecimal("99.00"), "alipay", null);

        assertThat(payment.getStatus()).isEqualTo(PaymentEntity.Status.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("余额不足");
        assertThat(payment.getPaidAt()).isNull();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceEntity.Status.OPEN);
        verify(invoiceRepository, never()).save(any(InvoiceEntity.class));
    }

    @Test
    @DisplayName("recordPayment：manual 网关 → PENDING，confirmPayment 后置 SUCCEEDED")
    void recordPayment_pending_thenConfirm() {
        when(paymentGatewayRegistry.resolve("manual")).thenReturn(new ManualPaymentGateway());
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.findById("pay-1")).thenAnswer(inv -> {
            PaymentEntity p = new PaymentEntity();
            p.setId("pay-1");
            p.setTenantId("t1");
            p.setInvoiceId("inv-1");
            p.setStatus(PaymentEntity.Status.PENDING);
            return Optional.of(p);
        });
        when(invoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice("inv-1", "t1")));
        when(invoiceRepository.save(any(InvoiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentEntity pending = service.recordPayment("t1", "inv-1", "sub-1",
                new BigDecimal("99.00"), "manual", null);
        assertThat(pending.getStatus()).isEqualTo(PaymentEntity.Status.PENDING);

        PaymentEntity confirmed = service.confirmPayment("pay-1", "t1");
        assertThat(confirmed.getStatus()).isEqualTo(PaymentEntity.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("recordPayment：未注册支付方式 → 抛错，绝不假装成功")
    void recordPayment_unknownProvider_throws() {
        when(paymentGatewayRegistry.resolve("bitcoin"))
                .thenThrow(new UnsupportedPaymentProviderException("bitcoin", java.util.Set.of("manual")));

        assertThatThrownBy(() -> service.recordPayment("t1", "inv-1", null,
                new BigDecimal("1"), "bitcoin", null))
                .isInstanceOf(UnsupportedPaymentProviderException.class);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("generateInvoice：明细用 Jackson 序列化（不再依赖缺失的 org.json）")
    void generateInvoice_serializesLineItems() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("t1");
        sub.setCurrentPeriodStart(LocalDate.of(2026, 1, 1));
        sub.setCurrentPeriodEnd(LocalDate.of(2026, 2, 1));
        when(subscriptionRepository.findById("sub-1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.count()).thenReturn(7L);
        when(invoiceRepository.save(any(InvoiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceEntity invoice = service.generateInvoice("t1", "sub-1",
                List.of(Map.of("desc", "专业版月费", "qty", 1, "amount", new BigDecimal("99"))),
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(invoice.getLineItems()).contains("专业版月费").contains("99");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("99");
        assertThat(invoice.getInvoiceNumber()).startsWith("INV-");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceEntity.Status.OPEN);
    }

    @Test
    @DisplayName("scanOverdueSubscriptions：按租户遍历，过期订阅置 EXPIRED")
    void scanOverdueSubscriptions_marksExpired() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("t1");
        sub.setStatus(SubscriptionEntity.Status.ACTIVE);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant("t1")));
        when(subscriptionRepository.findByTenantIdAndStatusAndCurrentPeriodEndLessThanEqual(
                eq("t1"), eq(SubscriptionEntity.Status.ACTIVE), any(LocalDate.class)))
                .thenReturn(List.of(sub));

        int expired = service.scanOverdueSubscriptions();

        assertThat(expired).isEqualTo(1);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionEntity.Status.EXPIRED);
        verify(subscriptionRepository).saveAll(List.of(sub));
    }

    @Test
    @DisplayName("scanOverdueInvoices：按租户遍历，逾期账单置 UNCOLLECTIBLE")
    void scanOverdueInvoices_marksUncollectible() {
        InvoiceEntity invoice = invoice("inv-1", "t1");
        when(tenantRepository.findAll()).thenReturn(List.of(tenant("t1")));
        when(invoiceRepository.findByTenantIdAndStatusAndDueDateLessThanEqual(
                eq("t1"), eq(InvoiceEntity.Status.OPEN), any(LocalDate.class)))
                .thenReturn(List.of(invoice));

        int overdue = service.scanOverdueInvoices();

        assertThat(overdue).isEqualTo(1);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceEntity.Status.UNCOLLECTIBLE);
        verify(invoiceRepository).saveAll(List.of(invoice));
    }

    @Test
    @DisplayName("扫描作业：无租户时不做任何写操作")
    void scanWithoutTenants_isNoop() {
        when(tenantRepository.findAll()).thenReturn(List.of());

        assertThat(service.scanOverdueSubscriptions()).isZero();
        assertThat(service.scanOverdueInvoices()).isZero();

        verify(subscriptionRepository, never()).saveAll(anyList());
        verify(invoiceRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("listAvailablePlans / getPlanById 直接透传仓储")
    void planQueries() {
        when(planTierRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(plan("plan_pro", "pro")));
        when(planTierRepository.findById("plan_pro")).thenReturn(Optional.of(plan("plan_pro", "pro")));

        assertThat(service.listAvailablePlans()).hasSize(1);
        assertThat(service.getPlanById("plan_pro")).isPresent();
        assertThat(service.getPlanById("nope")).isEmpty();
    }

    @Test
    @DisplayName("getPayments / supportedPaymentProviders 透传")
    void paymentQueries() {
        when(paymentRepository.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of(new PaymentEntity()));
        when(paymentGatewayRegistry.supportedProviders()).thenReturn(java.util.Set.of("manual"));

        assertThat(service.getPayments("t1")).hasSize(1);
        assertThat(service.supportedPaymentProviders()).containsExactly("manual");
    }

    @Test
    @DisplayName("createSubscription(trial=true) → TRIALING 且不设下次扣费日")
    void createSubscription_trial() {
        when(subscriptionRepository.findByTenantIdAndStatusIn(eq("t1"), anyList())).thenReturn(Optional.empty());
        when(planTierRepository.findById("plan_pro")).thenReturn(Optional.of(plan("plan_pro", "pro")));
        when(subscriptionRepository.save(any(SubscriptionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity saved = service.createSubscription("t1", "plan_pro", "manual", true);

        assertThat(saved.getStatus()).isEqualTo(SubscriptionEntity.Status.TRIALING);
        assertThat(saved.getNextBillingDate()).isNull();
    }

    @Test
    @DisplayName("upgradeSubscription 同租户 → 切换套餐")
    void upgradeSubscription_ok() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("t1");
        when(subscriptionRepository.findById("sub-1")).thenReturn(Optional.of(sub));
        when(planTierRepository.findById("plan_pro")).thenReturn(Optional.of(plan("plan_pro", "pro")));
        when(subscriptionRepository.save(any(SubscriptionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.upgradeSubscription("sub-1", "t1", "plan_pro").getPlanTierId()).isEqualTo("plan_pro");
    }

    @Test
    @DisplayName("cancelSubscriptionImmediately → CANCELED 并写入取消时间")
    void cancelImmediately() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("t1");
        when(subscriptionRepository.findById("sub-1")).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(SubscriptionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity canceled = service.cancelSubscriptionImmediately("sub-1", "t1");

        assertThat(canceled.getStatus()).isEqualTo(SubscriptionEntity.Status.CANCELED);
        assertThat(canceled.getCanceledAt()).isNotNull();
    }

    @Test
    @DisplayName("renewSubscription → 续期一个月且清除取消标记")
    void renew() {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId("sub-1");
        sub.setTenantId("t1");
        sub.setCurrentPeriodEnd(LocalDate.of(2026, 3, 1));
        sub.setCancelAtPeriodEnd(true);
        when(subscriptionRepository.findById("sub-1")).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(SubscriptionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity renewed = service.renewSubscription("sub-1", "t1");

        assertThat(renewed.getStatus()).isEqualTo(SubscriptionEntity.Status.ACTIVE);
        assertThat(renewed.getCurrentPeriodEnd()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(renewed.getCancelAtPeriodEnd()).isFalse();
    }

    @Test
    @DisplayName("markInvoiceAsPaid 同租户 → PAID")
    void markInvoicePaid_ok() {
        InvoiceEntity invoice = invoice("inv-1", "t1");
        when(invoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(InvoiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markInvoiceAsPaid("inv-1", "t1", java.time.Instant.now()).getStatus())
                .isEqualTo(InvoiceEntity.Status.PAID);
    }

    @Test
    @DisplayName("confirmPayment 跨租户 → 拒绝")
    void confirmPayment_crossTenant_denied() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId("pay-1");
        payment.setTenantId("other");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.confirmPayment("pay-1", "t1"))
                .isInstanceOf(BillingAccessDeniedException.class);
    }

    private static PlanTierEntity plan(String id, String slug) {
        PlanTierEntity p = new PlanTierEntity();
        p.setId(id);
        p.setSlug(slug);
        return p;
    }

    private static TenantEntity tenant(String id) {
        TenantEntity t = new TenantEntity();
        t.setId(id);
        return t;
    }

    private static InvoiceEntity invoice(String id, String tenantId) {
        InvoiceEntity inv = new InvoiceEntity();
        inv.setId(id);
        inv.setTenantId(tenantId);
        inv.setInvoiceNumber("INV-20260101-0001");
        inv.setStatus(InvoiceEntity.Status.OPEN);
        return inv;
    }

    /** 固定返回指定结果的测试网关。 */
    private record FixedGateway(PaymentGateway.PaymentResult result) implements PaymentGateway {

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public PaymentResult charge(ChargeRequest request) {
            return result;
        }
    }
}
