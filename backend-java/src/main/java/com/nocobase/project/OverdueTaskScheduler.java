package com.nocobase.project;

import com.nocobase.notification.NotificationDispatcher;
import com.nocobase.notification.NotificationService;
import com.nocobase.tenant.TenantEntity;
import com.nocobase.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 到期任务提醒调度器。
 *
 * <p>每小时检查一次 overdue task，为 assignee 发送提醒（站内消息 + 邮件）。
 *
 * <p><b>多租户</b>：调度器运行在请求上下文之外，没有 TenantContext，
 * 因此显式遍历所有 ACTIVE 租户，逐个带 tenantId 过滤查询，
 * 保证租户隔离不被绕过。
 *
 * <p><b>幂等</b>：按「任务 id + 提醒日」去重，同一任务当天只提醒一次，
 * 避免每小时重复轰炸 assignee。
 */
@Service
public class OverdueTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueTaskScheduler.class);

    /** 已完成状态，不计入过期。 */
    private static final String STATUS_DONE = "DONE";

    private final ProjectTaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TenantRepository tenantRepository;
    private final NotificationService notificationService;

    /** 幂等去重：key = tenantId + taskId + yyyy-MM-dd。进程内即可满足单副本；多副本由 DB 唯一约束兜底。 */
    private final Set<String> remindedKeys = Collections.synchronizedSet(new HashSet<>());

    public OverdueTaskScheduler(ProjectTaskRepository taskRepository,
                                ProjectRepository projectRepository,
                                TenantRepository tenantRepository,
                                NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.tenantRepository = tenantRepository;
        this.notificationService = notificationService;
    }

    /** 每小时检查一次过期任务。 */
    @Scheduled(fixedDelay = 3600000, initialDelay = 1800000)
    public void checkOverdueTasks() {
        List<TenantEntity> tenants = tenantRepository.findByStatus(TenantEntity.Status.ACTIVE);
        if (tenants.isEmpty()) {
            log.debug("[OverdueTask] 无 ACTIVE 租户，跳过");
            return;
        }
        Instant now = Instant.now();
        for (TenantEntity tenant : tenants) {
            scanTenant(tenant.getId(), now);
        }
    }

    /** 扫描单个租户的过期任务并按 assignee 聚合发送提醒。 */
    private void scanTenant(String tenantId, Instant now) {
        List<ProjectTaskEntity> overdue = taskRepository
                .findByTenantIdAndEndDateBeforeAndStatusNotOrderByEndDateAsc(tenantId, now, STATUS_DONE);
        if (overdue.isEmpty()) {
            return;
        }

        String today = java.time.LocalDate.now().toString();
        Map<UUID, List<ProjectTaskEntity>> byAssignee = new LinkedHashMap<>();
        for (ProjectTaskEntity task : overdue) {
            if (task.getAssigneeId() == null) continue;
            // 幂等：同一任务当天已提醒过则跳过
            if (!remindedKeys.add(tenantId + ':' + task.getId() + ':' + today)) continue;
            byAssignee.computeIfAbsent(task.getAssigneeId(), k -> new ArrayList<>()).add(task);
        }

        for (Map.Entry<UUID, List<ProjectTaskEntity>> entry : byAssignee.entrySet()) {
            String assigneeId = entry.getKey().toString();
            List<ProjectTaskEntity> tasks = entry.getValue();

            List<Map<String, Object>> taskViews = new ArrayList<>();
            for (ProjectTaskEntity task : tasks) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", task.getId().toString());
                view.put("title", task.getTitle());
                view.put("endDate", task.getEndDate() == null ? null : task.getEndDate().toString());
                view.put("projectName", resolveProjectName(task.getProjectId()));
                taskViews.add(view);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "overdue");
            payload.put("taskCount", tasks.size());
            payload.put("tasks", taskViews);
            payload.put("timestamp", now.toString());

            List<NotificationDispatcher.SendResult> results =
                    notificationService.fire(tenantId, "task.overdue", assigneeId, payload);

            boolean ok = !results.isEmpty() && results.stream()
                    .allMatch(NotificationDispatcher.SendResult::success);
            log.info("[OverdueTask] tenant={} assignee={} count={} notified={}",
                    tenantId, assigneeId, tasks.size(), ok);
        }
    }

    private String resolveProjectName(UUID projectId) {
        if (projectId == null) return null;
        return projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
    }
}