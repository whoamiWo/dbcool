package com.nocobase.workflow;

import java.util.List;
import java.util.Map;

/**
 * 工作流模板(US-410).
 * 一个模板是一个完整的「collection + workflow」单元,用户一键应用即可获得
 * 一个可用的业务场景(请假/报销等),无需手动建 collection + 设计 workflow。
 *
 * <p>POJO 字段对应 JSON:
 * <pre>
 * {
 *   "key": "leave_approval",
 *   "name": "请假审批",
 *   "category": "HR",
 *   "description": "员工提交请假 → 经理审批 → 通知",
 *   "icon": "📅",
 *   "collections": [
 *     {"name": "leave_request", "title": "请假申请", "fields": [...]}
 *   ],
 *   "workflow": {
 *     "name": "Leave Approval",
 *     "collection": "leave_request",
 *     "trigger": {"type":"record.created"},
 *     "nodes": [...]
 *   }
 * }
 * </pre>
 */
public record WorkflowTemplate(
        String key,
        String name,
        String category,
        String description,
        String icon,
        List<TemplateCollection> collections,
        TemplateWorkflow workflow
) {
    public record TemplateCollection(
            String name,
            String title,
            String description,
            List<Map<String, Object>> fields
    ) {}

    public record TemplateWorkflow(
            String name,
            String description,
            String collection,
            Map<String, Object> trigger,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges
    ) {}
}
