package com.nocobase.plugin;

/** R15: 插件 Manifest 校验异常. */
public class PluginValidationException extends RuntimeException {

    public PluginValidationException(String message) {
        super(message);
    }

    public PluginValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
