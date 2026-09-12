package com.nocobase.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 内置工作流模板注册表(US-410).
 *
 * <p>3 个内置模板,演示用:
 * <ul>
 *   <li>leave_approval — 请假申请 + 经理审批</li>
 *   <li>expense_report — 报销提交 + 财务审批</li>
 *   <li>customer_followup — 新增客户 + 自动通知跟进</li>
 * </ul>
 */
@Component
public class WorkflowTemplateRegistry {

    private final Map<String, WorkflowTemplate> templates = new LinkedHashMap<>();

    public WorkflowTemplateRegistry() {
        templates.put("leave_approval", leaveApproval());
        templates.put("expense_report", expenseReport());
        templates.put("customer_followup", customerFollowup());
    }

    public List<WorkflowTemplate> list() {
        return List.copyOf(templates.values());
    }

    public Optional<WorkflowTemplate> get(String key) {
        return Optional.ofNullable(templates.get(key));
    }

    // ============================================================
    //  1. leave_approval
    // ============================================================
    private WorkflowTemplate leaveApproval() {
        return new WorkflowTemplate(
                "leave_approval",
                "请假审批",
                "HR",
                "员工提交请假申请 → 自动创建审批任务通知经理 → 审批后通知员工",
                "📅",
                List.of(new WorkflowTemplate.TemplateCollection(
                        "leave_request",
                        "请假申请",
                        "员工请假申请单",
                        List.of(
                                field("title", "text", "事由"),
                                field("days", "number", "天数"),
                                field("reason", "text", "详细原因"),
                                field("status", "text", "状态"),
                                field("applicant", "text", "申请人")
                        ))),
                new WorkflowTemplate.TemplateWorkflow(
                        "Leave Approval",
                        "请假申请自动触发经理审批",
                        "leave_request",
                        Map.of("type", "record.created"),
                        List.of(
                                node("start", "Start", "manual", Map.of(), "end"),
                                node("notify_applicant", "Notify Applicant", "notification",
                                        Map.of("title", "申请已提交", "body", "您的请假申请已进入审批"), "end")
                        ),
                        List.of(
                                edge("start", "notify_applicant"),
                                edge("notify_applicant", "end")
                        )));
    }

    // ============================================================
    //  2. expense_report
    // ============================================================
    private WorkflowTemplate expenseReport() {
        return new WorkflowTemplate(
                "expense_report",
                "报销审批",
                "财务",
                "员工提交报销 → 财务审批 → 通过后通知员工",
                "💰",
                List.of(new WorkflowTemplate.TemplateCollection(
                        "expense_report",
                        "报销申请",
                        "员工报销申请单",
                        List.of(
                                field("title", "text", "事项"),
                                field("amount", "number", "金额"),
                                field("category", "text", "类别"),
                                field("status", "text", "状态"),
                                field("applicant", "text", "申请人")
                        ))),
                new WorkflowTemplate.TemplateWorkflow(
                        "Expense Approval",
                        "报销申请自动触发财务审批",
                        "expense_report",
                        Map.of("type", "record.created"),
                        List.of(
                                node("start", "Start", "manual", Map.of(), "review"),
                                node("review", "Auto Review", "condition",
                                        Map.of("condition", "amount > 1000"), "notify"),
                                node("notify", "Notify Finance", "notification",
                                        Map.of("title", "新报销待审批", "body", "收到新报销"), "end")
                        ),
                        List.of(
                                edge("start", "review"),
                                edge("review", "notify"),
                                edge("notify", "end")
                        )));
    }

    // ============================================================
    //  3. customer_followup
    // ============================================================
    private WorkflowTemplate customerFollowup() {
        return new WorkflowTemplate(
                "customer_followup",
                "新增客户跟进",
                "销售",
                "新增客户记录 → 自动通知销售跟进",
                "🤝",
                List.of(new WorkflowTemplate.TemplateCollection(
                        "customer",
                        "客户档案",
                        "客户基本信息",
                        List.of(
                                field("name", "text", "客户名"),
                                field("email", "text", "邮箱"),
                                field("phone", "text", "电话"),
                                field("status", "text", "状态")
                        ))),
                new WorkflowTemplate.TemplateWorkflow(
                        "Customer Followup",
                        "新客户自动通知销售跟进",
                        "customer",
                        Map.of("type", "record.created"),
                        List.of(
                                node("start", "Start", "manual", Map.of(), "notify"),
                                node("notify", "Notify Sales", "notification",
                                        Map.of("title", "新客户", "body", "请跟进"), "end")
                        ),
                        List.of(
                                edge("start", "notify"),
                                edge("notify", "end")
                        )));
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static Map<String, Object> field(String name, String type, String label) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", type);
        f.put("label", label);
        return f;
    }

    private static Map<String, Object> node(String id, String name, String type,
                                            Map<String, Object> config, String next) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("name", name);
        n.put("type", type);
        n.put("config", config);
        if (next != null) n.put("next", next);
        return n;
    }

    private static Map<String, Object> edge(String source, String target) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        return e;
    }
}
