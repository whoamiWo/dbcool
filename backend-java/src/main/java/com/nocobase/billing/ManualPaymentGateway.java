package com.nocobase.billing;

import org.springframework.stereotype.Component;

/**
 * 线下/人工支付网关 (Manual Payment Gateway).
 *
 * <p>用于转账、合同结算等不走在线渠道的场景：不产生任何"成功"假象，
 * 返回的 PaymentResult 为 pending，PaymentEntity 落库为 PENDING，
 * 需由财务确认后调用 {@code POST /api/billing/payments/{id}/confirm} 才置为 SUCCEEDED。</p>
 */
@Component
public class ManualPaymentGateway implements PaymentGateway {

    public static final String PROVIDER = "manual";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        return PaymentResult.pending("等待人工确认收款（manual 网关不执行在线扣款）");
    }
}
