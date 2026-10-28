package com.nocobase.billing;

import java.math.BigDecimal;

/**
 * 支付网关抽象 (Payment Gateway).
 *
 * <p>B4 之前 {@code BillingService#recordPayment} 直接把 PaymentEntity 置为 SUCCEEDED，
 * 等价于"调用方自称已付款"。此处抽象出真实网关契约：是否扣款成功由网关返回决定，
 * 未配置网关的支付方式一律拒绝，不再静默成功。</p>
 */
public interface PaymentGateway {

    /** 网关标识，小写，如 alipay / wechat / stripe / manual。 */
    String provider();

    /** 发起扣款。实现必须保证：仅当网关确认收款成功时才返回 {@code success=true}。 */
    PaymentResult charge(ChargeRequest request);

    /** 扣款请求。 */
    record ChargeRequest(
            String tenantId,
            String invoiceId,
            String subscriptionId,
            BigDecimal amount,
            String currency,
            String orderTitle) {
    }

    /** 扣款结果三态：成功 / 待确认 / 失败。 */
    record PaymentResult(State state, String transactionId, String failureReason, String rawResponse) {

        public enum State { SUCCEEDED, PENDING, FAILED }

        public static PaymentResult succeeded(String transactionId, String rawResponse) {
            return new PaymentResult(State.SUCCEEDED, transactionId, null, rawResponse);
        }

        /** 已受理但未确认收款（线下转账等），PaymentEntity 落 PENDING，需人工确认。 */
        public static PaymentResult pending(String reason) {
            return new PaymentResult(State.PENDING, null, reason, null);
        }

        public static PaymentResult failed(String reason, String rawResponse) {
            return new PaymentResult(State.FAILED, null, reason, rawResponse);
        }

        public boolean success() {
            return state == State.SUCCEEDED;
        }
    }
}
