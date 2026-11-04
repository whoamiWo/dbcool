package com.nocobase.notification;

import com.nocobase.project.ProjectTaskEntity;
import com.nocobase.project.ProjectTaskRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * E3 定时提醒（DUE_SOON）。
 *
 * <p>每天 09:00 扫描「未来 24 小时内到期且未完成」的项目任务，给处理人发提醒。
 * 用 {@code due-soon:taskId:yyyy-MM-dd} 作为 digest_key —— 同一天多次执行只会合并，
 * 不会刷屏（任务书 R5：通知风暴防护）。
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final ProjectTaskRepository taskRepository;
    private final NotifyDispatcher notifyDispatcher;

    public NotificationScheduler(ProjectTaskRepository taskRepository,
                                 NotifyDispatcher notifyDispatcher) {
        this.taskRepository = taskRepository;
        this.notifyDispatcher = notifyDispatcher;
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void remindDueSoon() {
        Instant now = Instant.now();
        Instant until = now.plus(24, ChronoUnit.HOURS);
        List<ProjectTaskEntity> tasks;
        try {
            tasks = taskRepository.findByEndDateBetweenAndStatusNot(now, until,
                    ProjectTaskEntity.Status.DONE.name());
        } catch (Exception e) {
            log.warn("[notify] DUE_SOON 任务查询失败: {}", e.getMessage());
            return;
        }
        int sent = 0;
        for (ProjectTaskEntity task : tasks) {
            if (task.getAssigneeId() == null || task.getTenantId() == null) continue;
            try {
                notifyDispatcher.notifyOne(NotifyDispatcher.DUE_SOON, task.getAssigneeId(),
                        new NotifyDispatcher.Payload(task.getTenantId(),
                                "任务即将到期", "任务「" + task.getTitle() + "」将在 24 小时内到期",
                                task.getId().toString(), "/projects",
                                "HIGH",
                                "due-soon:" + task.getId() + ":" + now.toString().substring(0, 10)));
                sent++;
            } catch (Exception e) {
                log.warn("[notify] DUE_SOON 发送失败 task={}: {}", task.getId(), e.getMessage());
            }
        }
        log.info("[notify] DUE_SOON 扫描完成 tasks={} sent={}", tasks.size(), sent);
    }
}
