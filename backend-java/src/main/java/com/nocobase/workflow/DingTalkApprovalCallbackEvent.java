package com.nocobase.workflow;

/**
 * Wave4 待办池 #10：钉钉审批回调事件（解耦）。
 *
 * <p>为什么用事件而非直接依赖：DingTalkApprovalService（dingtalk 包）若直接注入
 * WorkflowTaskService → WorkflowEngine → DingTalkApprovalNodeHandler →
 * DingTalkApprovalService，形成 Spring 无法解析的构造器循环依赖（实测）。
 * 改为发布 Spring 应用事件，由 WorkflowTaskService 以 @EventListener 消费。
 */
public record DingTalkApprovalCallbackEvent(String dingtalkInstanceId, boolean agree, String comment) {}
