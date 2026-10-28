package com.nocobase.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 计费业务服务 (Billing Service).
 * 
 * <p>封装订阅生命周期管理、账单生成、支付处理等业务逻辑。</p>
 *
 * <p>依赖全部通过构造器注入且强制非空：仓储缺失时启动即失败，
 * 避免出现 {@code @Autowired(required=false)} 那种"静默降级为 null、
 * 定时任务什么都不做"的假可用状态。</p>
 */
@Service
@Transactional
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final DateTimeFormatter INVOICE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PlanTierRepository planTierRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    public BillingService(PlanTierRepository planTierRepository,
                          SubscriptionRepository subscriptionRepository,
                          InvoiceRepository invoiceRepository,
                          PaymentRepository paymentRepository,
                          PaymentGatewayRegistry paymentGatewayRegistry,
                          TenantRepository tenantRepository,
                          ObjectMapper objectMapper) {
        this.planTierRepository = Objects.requireNonNull(planTierRepository, "planTierRepository");
        this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository");
        this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
        this.paymentGatewayRegistry = Objects.requireNonNull(paymentGatewayRegistry, "paymentGatewayRegistry");
        this.tenantRepository = Objects.requireNonNull(tenantRepository, "tenantRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** 初始化默认套餐 (仅在首次启动且无套餐时执行)。 */
    @PostConstruct
    public void initDefaultPlanTiers() {
        if (planTierRepository.count() == 0) {
            log.info("Initializing default plan tiers...");
            
            List<PlanTierEntity> plans = Arrays.asList(
                createPlanTier("plan_free", "免费版", "free", "适合个人和小团队体验", 
                              BigDecimal.ZERO, BigDecimal.ZERO, 1000L, 1_073_741_824L, 3L, 
                              "{\"basic_features\": true, \"community_support\": true}", 0),
                
                createPlanTier("plan_pro", "专业版", "pro", "适合成长型业务",
                              new BigDecimal("99"), new BigDecimal("990"), 100_000L, 10_737_418_240L, 10L,
                              "{\"ai_assistant\": true, \"advanced_analytics\": true, \"priority_support\": true}", 1),
                              
                createPlanTier("plan_enterprise", "企业版", "enterprise", "适合大型组织",
                              new BigDecimal("499"), new BigDecimal("4990"), 0L, 107_374_182_400L, 100L,
                              "{\"ai_assistant\": true, \"advanced_analytics\": true, \"custom_branding\": true, \"dedicated_support\": true, \"sla\": true}", 2)
            );
            
            planTierRepository.saveAll(plans);
            log.info("Default plan tiers initialized.");
        }
    }

    private PlanTierEntity createPlanTier(String id, String name, String slug, String description,
                                          BigDecimal monthly, BigDecimal yearly, Long apiLimit,
                                          Long storageLimit, Long seatsLimit, String features, int order) {
        PlanTierEntity entity = new PlanTierEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setDescription(description);
        entity.setPriceMonthly(monthly);
        entity.setPriceYearly(yearly);
        entity.setApiCallLimit(apiLimit);
        entity.setStorageLimit(storageLimit);
        entity.setSeatsLimit(seatsLimit);
        entity.setFeatures(features);
        entity.setSortOrder(order);
        entity.setIsActive(true);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    /** 获取所有可用套餐。 */
    public List<PlanTierEntity> listAvailablePlans() {
        return planTierRepository.findAllByOrderBySortOrderAsc();
    }

    /** 获取指定套餐详情。 */
    public Optional<PlanTierEntity> getPlanById(String planId) {
        return planTierRepository.findById(planId);
    }

    /** 创建新订阅。 */
    public SubscriptionEntity createSubscription(String tenantId, String planTierId, 
                                                  String paymentMethod, boolean trial) {
        // billing_subscription 上有 (tenant_id) WHERE status IN ('active','trialing') 的部分唯一索引，
        // 这里先占位校验，避免把约束冲突暴露成 500。
        Optional<SubscriptionEntity> existing = getActiveSubscription(tenantId);
        if (existing.isPresent()) {
            throw new IllegalStateException(
                    "租户已存在生效中的订阅: " + existing.get().getId() + "，请先取消或续订");
        }

        PlanTierEntity plan = planTierRepository.findById(planTierId)
            .orElseThrow(() -> new IllegalArgumentException("Plan tier not found: " + planTierId));

        LocalDate today = LocalDate.now();
        
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.setId(UUID.randomUUID().toString());
        subscription.setTenantId(tenantId);
        subscription.setPlanTierId(planTierId);
        subscription.setStatus(trial ? SubscriptionEntity.Status.TRIALING : SubscriptionEntity.Status.ACTIVE);
        subscription.setStartDate(today);
        subscription.setCurrentPeriodStart(today);
        
        // 计算计费周期结束日期 (按月)
        LocalDate periodEnd = today.plusMonths(1);
        subscription.setCurrentPeriodEnd(periodEnd);
        
        if (!trial) {
            subscription.setNextBillingDate(periodEnd);
        }
        
        subscription.setPaymentMethod(paymentMethod);
        subscription.setCancelAtPeriodEnd(false);
        subscription.setCreatedAt(Instant.now());
        subscription.setUpdatedAt(Instant.now());

        log.info("Subscription created for tenant {} on plan {}", tenantId, plan.getSlug());
        return subscriptionRepository.save(subscription);
    }

    /** 升级/降级订阅套餐（校验订阅归属当前租户）。 */
    public SubscriptionEntity upgradeSubscription(String subscriptionId, String tenantId, String newPlanTierId) {
        SubscriptionEntity subscription = requireOwnedSubscription(subscriptionId, tenantId);

        PlanTierEntity newPlan = planTierRepository.findById(newPlanTierId)
            .orElseThrow(() -> new IllegalArgumentException("New plan tier not found"));

        subscription.setPlanTierId(newPlanTierId);
        subscription.setUpdatedAt(Instant.now());

        log.info("Subscription {} upgraded to plan {}", subscriptionId, newPlan.getId());
        return subscriptionRepository.save(subscription);
    }

    /** 取消订阅 (周期末生效，校验归属)。 */
    public SubscriptionEntity cancelSubscriptionAtPeriodEnd(String subscriptionId, String tenantId) {
        SubscriptionEntity subscription = requireOwnedSubscription(subscriptionId, tenantId);

        subscription.setCancelAtPeriodEnd(true);
        subscription.setUpdatedAt(Instant.now());

        return subscriptionRepository.save(subscription);
    }

    /** 立即取消订阅（校验归属）。 */
    public SubscriptionEntity cancelSubscriptionImmediately(String subscriptionId, String tenantId) {
        SubscriptionEntity subscription = requireOwnedSubscription(subscriptionId, tenantId);

        subscription.setStatus(SubscriptionEntity.Status.CANCELED);
        subscription.setCanceledAt(Instant.now());
        subscription.setCancelAtPeriodEnd(false);
        subscription.setUpdatedAt(Instant.now());

        log.info("Subscription {} canceled immediately", subscriptionId);
        return subscriptionRepository.save(subscription);
    }

    /** 续订订阅 (调用方需先确认支付成功，校验归属)。 */
    public SubscriptionEntity renewSubscription(String subscriptionId, String tenantId) {
        SubscriptionEntity subscription = requireOwnedSubscription(subscriptionId, tenantId);

        LocalDate currentEnd = subscription.getCurrentPeriodEnd();
        
        subscription.setCurrentPeriodStart(currentEnd);
        subscription.setCurrentPeriodEnd(currentEnd.plusMonths(1));
        subscription.setNextBillingDate(currentEnd.plusMonths(1));
        subscription.setStatus(SubscriptionEntity.Status.ACTIVE);
        subscription.setCancelAtPeriodEnd(false);
        subscription.setCanceledAt(null);
        subscription.setUpdatedAt(Instant.now());

        log.info("Subscription {} renewed until {}", subscriptionId, subscription.getCurrentPeriodEnd());
        return subscriptionRepository.save(subscription);
    }

    /** 获取租户当前订阅。 */
    public Optional<SubscriptionEntity> getActiveSubscription(String tenantId) {
        List<SubscriptionEntity.Status> activeStatuses = Arrays.asList(
            SubscriptionEntity.Status.ACTIVE, SubscriptionEntity.Status.TRIALING
        );
        return subscriptionRepository.findByTenantIdAndStatusIn(tenantId, activeStatuses);
    }

    /** 获取租户历史订阅。 */
    public List<SubscriptionEntity> getSubscriptions(String tenantId) {
        return subscriptionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 生成账单 (通常在周期结束时调用)。 */
    public InvoiceEntity generateInvoice(String tenantId, String subscriptionId, 
                                         List<Map<String, Object>> lineItems,
                                         BigDecimal discount, BigDecimal tax) {
        SubscriptionEntity subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));

        LocalDate today = LocalDate.now();
        LocalDate periodStart = subscription.getCurrentPeriodStart();
        LocalDate periodEnd = subscription.getCurrentPeriodEnd();

        // 计算金额
        BigDecimal subtotal = lineItems.stream()
            .map(item -> new BigDecimal(item.get("amount").toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal total = subtotal.subtract(discount).add(tax);

        // 生成发票编号：日期 + 当日序号(取当日已有账单数，避免随机撞号后直接 500)
        long seq = invoiceRepository.count();
        String invoiceNumber = String.format("INV-%s-%04d", today.format(INVOICE_DATE), seq % 10_000L);

        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId(UUID.randomUUID().toString());
        invoice.setTenantId(tenantId);
        invoice.setSubscriptionId(subscriptionId);
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodEnd);
        invoice.setAmount(subtotal);
        invoice.setDiscountAmount(discount);
        invoice.setTaxAmount(tax);
        invoice.setTotalAmount(total);
        invoice.setCurrency("CNY");
        invoice.setStatus(InvoiceEntity.Status.OPEN);
        invoice.setDueDate(today.plusDays(15));
        invoice.setLineItems(convertToJson(lineItems));
        invoice.setCreatedAt(Instant.now());
        invoice.setUpdatedAt(Instant.now());

        return invoiceRepository.save(invoice);
    }

    /** 标记账单为已支付（校验归属）。 */
    public InvoiceEntity markInvoiceAsPaid(String invoiceId, String tenantId, Instant paidAt) {
        InvoiceEntity invoice = requireOwnedInvoice(invoiceId, tenantId);

        invoice.setStatus(InvoiceEntity.Status.PAID);
        invoice.setPaidAt(paidAt);
        invoice.setUpdatedAt(Instant.now());

        return invoiceRepository.save(invoice);
    }

    /** 获取租户账单列表。 */
    public List<InvoiceEntity> getInvoices(String tenantId) {
        return invoiceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 获取未支付账单。 */
    public List<InvoiceEntity> getUnpaidInvoices(String tenantId) {
        List<InvoiceEntity.Status> unpaidStatuses = Arrays.asList(
            InvoiceEntity.Status.DRAFT, InvoiceEntity.Status.OPEN
        );
        return invoiceRepository.findByTenantIdAndStatusIn(tenantId, unpaidStatuses);
    }

    /**
     * 发起支付：真实调用支付网关，扣款结果由网关返回决定。
     *
     * <p>网关未配置 / 网关明确失败时，PaymentEntity 落为 FAILED 并保留失败原因；
     * 仅网关返回成功态时才置 SUCCEEDED 并把关联账单标记已支付。</p>
     */
    public PaymentEntity recordPayment(String tenantId, String invoiceId, String subscriptionId,
                                       BigDecimal amount, String paymentMethod, String transactionId) {
        PaymentGateway gateway = paymentGatewayRegistry.resolve(paymentMethod);

        String orderTitle = "NocoBase 账单支付";
        if (StringUtils.hasText(invoiceId)) {
            orderTitle = invoiceRepository.findById(invoiceId)
                    .map(inv -> "NocoBase 账单 " + inv.getInvoiceNumber())
                    .orElse(orderTitle);
        }

        PaymentGateway.PaymentResult result = gateway.charge(new PaymentGateway.ChargeRequest(
                tenantId, invoiceId, subscriptionId, amount, "CNY", orderTitle));

        PaymentEntity payment = new PaymentEntity();
        payment.setId(UUID.randomUUID().toString());
        payment.setTenantId(tenantId);
        payment.setInvoiceId(invoiceId);
        payment.setSubscriptionId(subscriptionId);
        payment.setAmount(amount);
        payment.setCurrency("CNY");
        payment.setPaymentMethod(paymentMethod);
        payment.setTransactionId(result.transactionId() != null ? result.transactionId() : transactionId);
        payment.setStatus(switch (result.state()) {
            case SUCCEEDED -> PaymentEntity.Status.SUCCEEDED;
            case PENDING -> PaymentEntity.Status.PENDING;
            case FAILED -> PaymentEntity.Status.FAILED;
        });
        payment.setFailureReason(result.failureReason());
        payment.setMetadata(result.rawResponse());
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());

        if (result.success()) {
            payment.setPaidAt(Instant.now());
            if (StringUtils.hasText(invoiceId)) {
                markInvoiceAsPaid(invoiceId, tenantId, Instant.now());
            }
            log.info("Payment {} succeeded via {} for invoice {}", payment.getId(), paymentMethod, invoiceId);
        } else {
            log.warn("Payment via {} failed for invoice {}: {}", paymentMethod, invoiceId, result.failureReason());
        }

        return paymentRepository.save(payment);
    }

    /** 人工确认支付成功（manual 网关落库为 PENDING 后由财务确认，校验归属）。 */
    public PaymentEntity confirmPayment(String paymentId, String tenantId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        if (!Objects.equals(payment.getTenantId(), tenantId)) {
            throw new BillingAccessDeniedException("支付记录不属于当前租户");
        }

        payment.setStatus(PaymentEntity.Status.SUCCEEDED);
        payment.setPaidAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        if (StringUtils.hasText(payment.getInvoiceId())) {
            markInvoiceAsPaid(payment.getInvoiceId(), tenantId, Instant.now());
        }
        return paymentRepository.save(payment);
    }

    /** 获取租户支付记录。 */
    public List<PaymentEntity> getPayments(String tenantId) {
        return paymentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 已注册的支付方式（供前端渲染可选渠道）。 */
    public Set<String> supportedPaymentProviders() {
        return paymentGatewayRegistry.supportedProviders();
    }

    /**
     * 扫描逾期订阅并更新状态。
     *
     * <p>按租户遍历执行（查询条件始终带 tenantId），避免无租户条件的全表扫描被解读为
     * 越权访问；同时日志可定位到具体租户。</p>
     */
    @Transactional
    public int scanOverdueSubscriptions() {
        LocalDate today = LocalDate.now();
        int total = 0;

        for (TenantEntity tenant : tenantRepository.findAll()) {
            String tenantId = tenant.getId();
            List<SubscriptionEntity> expired = subscriptionRepository
                .findByTenantIdAndStatusAndCurrentPeriodEndLessThanEqual(
                    tenantId, SubscriptionEntity.Status.ACTIVE, today);

            for (SubscriptionEntity sub : expired) {
                sub.setStatus(SubscriptionEntity.Status.EXPIRED);
                sub.setUpdatedAt(Instant.now());
                log.warn("Subscription {} of tenant {} expired", sub.getId(), tenantId);
            }

            if (!expired.isEmpty()) {
                subscriptionRepository.saveAll(expired);
                total += expired.size();
            }
        }

        if (total > 0) {
            log.info("Overdue subscription scan finished, {} expired", total);
        }
        return total;
    }

    /** 扫描逾期账单并更新状态（同样按租户遍历）。 */
    @Transactional
    public int scanOverdueInvoices() {
        LocalDate today = LocalDate.now();
        int total = 0;

        for (TenantEntity tenant : tenantRepository.findAll()) {
            String tenantId = tenant.getId();
            List<InvoiceEntity> overdue = invoiceRepository
                .findByTenantIdAndStatusAndDueDateLessThanEqual(
                    tenantId, InvoiceEntity.Status.OPEN, today);

            for (InvoiceEntity inv : overdue) {
                inv.setStatus(InvoiceEntity.Status.UNCOLLECTIBLE);
                inv.setUpdatedAt(Instant.now());
                log.warn("Invoice {} of tenant {} marked as uncollectible", inv.getInvoiceNumber(), tenantId);
            }

            if (!overdue.isEmpty()) {
                invoiceRepository.saveAll(overdue);
                total += overdue.size();
            }
        }

        if (total > 0) {
            log.info("Overdue invoice scan finished, {} uncollectible", total);
        }
        return total;
    }

    // --- 归属校验：跨租户访问一律拒绝，避免仅靠 path 传 tenantId 就能读他人账单 ---

    private SubscriptionEntity requireOwnedSubscription(String subscriptionId, String tenantId) {
        SubscriptionEntity subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        if (!Objects.equals(subscription.getTenantId(), tenantId)) {
            throw new BillingAccessDeniedException("订阅不属于当前租户: " + subscriptionId);
        }
        return subscription;
    }

    private InvoiceEntity requireOwnedInvoice(String invoiceId, String tenantId) {
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        if (!Objects.equals(invoice.getTenantId(), tenantId)) {
            throw new BillingAccessDeniedException("账单不属于当前租户: " + invoiceId);
        }
        return invoice;
    }

    private String convertToJson(List<Map<String, Object>> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.error("Failed to convert line items to JSON", e);
            return "[]";
        }
    }
}
