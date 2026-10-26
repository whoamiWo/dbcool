package com.nocobase.integration.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一 JS-SDK 签名服务（钉钉 / 企业微信 / 飞书工作台嵌入）。
 *
 * <p>三平台签名算法一致：{@code SHA1("jsapi_ticket=T&noncestr=N&timestamp=TS&url=U")}，
 * 差异仅在 ticket 的获取方式（钉钉 jsapi_ticket、企微 corp/agent ticket、飞书 app ticket）。
 *
 * <p>用途：前端拿到签名后调用 {@code dd.config} / {@code wx.config} / {@code wx.agentConfig} /
 * 飞书 {@code h5Webview} 完成鉴权，从而在工作台内打开本平台页面并调用原生能力
 * （选人、拍照、定位、分享）。
 */
public interface JsSignatureProvider {

    /** provider 标识：dingtalk / wecom / feishu */
    String provider();

    /**
     * 生成前端 JS-SDK 配置签名（默认 ticket 类型）。
     *
     * @param tenantId 租户 ID
     * @param url      当前页面完整 URL（不含 {@code #} 及其后部分），必须与前端
     *                  {@code location.href.split('#')[0]} 一致，否则签名校验失败
     */
    default JsSignature sign(String tenantId, String url) {
        return sign(tenantId, url, "default");
    }

    /**
     * 按指定 ticket 类型生成签名。
     *
     * @param type ticket 类型：钉钉固定 {@code default}；企微 {@code corp}（wx.config）
     *             或 {@code agent}（wx.agentConfig）；飞书固定 {@code default}
     */
    JsSignature sign(String tenantId, String url, String type);

    /** 签名结果（字段对齐各平台 JS-SDK config 入参）。 */
    record JsSignature(
            String appId,
            String nonceStr,
            long timestamp,
            String url,
            String signature,
            /** ticket 类型：default / corp / agent */
            String ticketType
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("appId", appId);
            m.put("nonceStr", nonceStr);
            m.put("timestamp", timestamp);
            m.put("url", url);
            m.put("signature", signature);
            m.put("type", ticketType);
            return m;
        }
    }
}
