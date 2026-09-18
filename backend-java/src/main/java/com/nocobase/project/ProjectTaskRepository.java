package com.nocobase.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectTaskRepository extends JpaRepository<ProjectTaskEntity, UUID> {

    /** 项目下全部任务(按排序与创建时间)。 */
    List<ProjectTaskEntity> findByTenantIdAndProjectIdOrderBySortOrderAscCreatedAtAsc(
            String tenantId, UUID projectId);

    /** 项目下的根任务(parent_id IS NULL)— 甘特图顶层。 */
    List<ProjectTaskEntity> findByTenantIdAndProjectIdAndParentIdIsNullOrderBySortOrderAsc(
            String tenantId, UUID projectId);

    /** 子任务。 */
    List<ProjectTaskEntity> findByTenantIdAndParentIdOrderBySortOrderAsc(
            String tenantId, UUID parentId);

    /** 按状态筛选(看板列)。 */
    List<ProjectTaskEntity> findByTenantIdAndProjectIdAndStatusOrderBySortOrderAsc(
            String tenantId, UUID projectId, String status);

    /** 按负责人(我的任务)。 */
    List<ProjectTaskEntity> findByTenantIdAndAssigneeIdOrderByEndDateAsc(
            String tenantId, UUID assigneeId);

    /** 项目下处于某个时间窗口内的任务(甘特图区间筛选用)。 */
    List<ProjectTaskEntity> findByTenantIdAndProjectIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            String tenantId, UUID projectId, java.time.Instant windowEnd, java.time.Instant windowStart);
}
