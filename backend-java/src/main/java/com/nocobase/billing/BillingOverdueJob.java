package com.nocobase.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 计费逾期扫描定时任务 (Billing Overdue Job).
 *
 * <p>修复前 {@code scanOverdueSubscriptions/scanOverdueInvoices} 没有任何
 * {@code @Scheduled} 接线，只能靠人工 POST /api/billing/scan-overdue 触发，
 * 生产环境等于永远不会跑。这里按天扫描：订阅过期 → EXPIRED，账单逾期 → UNCOLLECTIBLE。</p>
 *
 * <p>与计费能力同开关：{@code billing.enabled=true} 才装配，个人自用默认不跑。</p>
 */
@ConditionalOnProperty(prefix = "billing", name = "enabled", havingValue = "true")
@Component
public class BillingOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(BillingOverdueJob.class);

    private final BillingService billingService;

    public BillingOverdueJob(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 每天 02:15 扫描逾期订阅与账单。 */
    @Scheduled(cron = "${billing.overdue-scan.cron:0 15 2 * * *}")
    public void scanOverdue() {
        try {
            billingService.scanOverdueSubscriptions();
            billingService.scanOverdueInvoices();
            log.info("Billing overdue scan completed");
        } catch (Exception e) {
            // 定时作业异常不能吞，也不能让调度线程静默退出
            log.error("Billing overdue scan failed", e);
        }
    }
}
