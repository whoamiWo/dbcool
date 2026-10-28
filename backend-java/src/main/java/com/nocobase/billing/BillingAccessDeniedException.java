package com.nocobase.billing;

/** 跨租户访问计费资源时抛出，由 Controller 转成 403。 */
public class BillingAccessDeniedException extends RuntimeException {

    public BillingAccessDeniedException(String message) {
        super(message);
    }
}
