package com.nocobase.integration.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 钉钉适配器 — 统一对外门面。
 *
 * <p>SSO / 用户同步 / 组织架构统一委托 {@link DingTalkAppService}(单一实现,
 * 避免此前两套并存且一套是占位的状况);本类额外承载 OA 审批回调入口。
 */
@Service
public class DingTalkAdapter {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAdapter.class);

    private final DingTalkAppService appService;

    public DingTalkAdapter(DingTalkAppService appService) {
        this.appService = appService;
    }

    /**
     * 钉钉免密登录:code 交换 → 本地账号同步。
     *
     * @return 与 {@link DingTalkAppService#exchangeCode} 一致的结构(data 含 userId/unionid)
     */
    public Map<String, Object> ssoLogin(String code, String tenantId) {
        return appService.exchangeCode(code, tenantId);
    }

    /** 组织架构同步。 */
    public Map<String, Object> syncOrganization(String tenantId) {
        return appService.syncOrganization(tenantId);
    }

    /**
     * OA 审批回调:接收钉钉审批实例结果。
     *
     * <p>钉钉推送的字段包含 {@code instance_id / result(agree|refuse) / finish_time}。
     * 当前记录审计日志;接入具体工作流实例时,可依据 instance_id 回查并推进节点。
     */
    public void onApprovalCallback(Map<String, Object> payload, String tenantId) {
        Object instanceId = payload == null ? null : payload.get("instance_id");
        Object result = payload == null ? null : payload.get("result");
        log.info("[DingTalk] OA 审批回调: tenant={}, instance={}, result={}",
                tenantId, instanceId, result);
    }

    public boolean isConfigured() {
        return appService.isConfigured();
    }
}
