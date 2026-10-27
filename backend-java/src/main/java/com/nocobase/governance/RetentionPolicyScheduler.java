package com.nocobase.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留策略调度器(B7)。
 *
 * <p>每天 02:00 扫描全部启用策略,执行到期者。
 * 依赖全局 {@code @EnableScheduling}(应用主类已开启)。
 */
@Component
public class RetentionPolicyScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyScheduler.class);

    private final DataGovernanceService governance;

    public RetentionPolicyScheduler(DataGovernanceService governance) {
        this.governance = governance;
    }

    /** 每天 02:00 执行到期保留策略。 */
    @Scheduled(cron = "0 0 2 * * *")
    public void run() {
        int executed = governance.executeAllDuePolicies();
        log.info("[governance] 保留策略调度执行完成,处理 {} 条策略", executed);
    }
}
