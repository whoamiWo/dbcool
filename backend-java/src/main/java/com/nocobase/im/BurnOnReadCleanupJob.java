package com.nocobase.im;

import com.nocobase.im.entity.ImMessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Burn-on-Read 定时清理任务。
 *
 * <p>每 5 分钟扫描过期消息（expires_at < now）并软删除。
 * 异常必须吞掉，不能影响业务主流程。
 */
@Component
public class BurnOnReadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(BurnOnReadCleanupJob.class);

    private final ImMessageRepository messageRepository;

    public BurnOnReadCleanupJob(ImMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @Transactional
    public void cleanupExpired() {
        try {
            Instant now = Instant.now();
            List<ImMessageEntity> expired = messageRepository.findByExpiresAtBeforeAndDeletedAtIsNull(now);
            if (expired.isEmpty()) return;
            for (ImMessageEntity m : expired) {
                m.setDeletedAt(now);
            }
            messageRepository.saveAll(expired);
            log.info("[burn] 清理了 {} 条过期消息", expired.size());
        } catch (Exception e) {
            log.warn("[burn] 清理过期消息失败,已降级: {}", e.getMessage());
        }
    }
}