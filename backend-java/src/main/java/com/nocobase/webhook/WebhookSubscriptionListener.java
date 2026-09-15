package com.nocobase.webhook;

import com.nocobase.event.RecordChangeEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听记录变更事件并推送 Webhook(Week 41 复核 D5.3)。
 *
 * <p>与工作流触发器共用同一套事件与语义:
 * {@code AFTER_COMMIT + fallbackExecution + @Async} ——
 * 不阻塞主流程,且记录 CRUD 无事务时也不会丢事件。
 */
@Component
public class WebhookSubscriptionListener {

    private final WebhookSubscriptionService service;

    public WebhookSubscriptionListener(WebhookSubscriptionService service) {
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecordChange(RecordChangeEvent event) {
        service.dispatch(event);
    }
}
