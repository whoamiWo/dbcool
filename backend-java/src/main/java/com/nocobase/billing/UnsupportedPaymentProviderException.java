package com.nocobase.billing;

import java.util.Set;

/** 支付方式没有对应网关时抛出（避免无网关时静默标记支付成功）。 */
public class UnsupportedPaymentProviderException extends RuntimeException {

    private final transient Set<String> supported;

    public UnsupportedPaymentProviderException(String provider, Set<String> supported) {
        super("支付方式未配置网关: " + provider + "，已注册网关: " + supported);
        this.supported = supported;
    }

    public Set<String> supported() {
        return supported;
    }
}
