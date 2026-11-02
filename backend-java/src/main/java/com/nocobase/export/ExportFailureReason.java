package com.nocobase.export;

/**
 * 导出失败的分类（用于返回不同的 HTTP code 与消息，便于冒烟测试区分 SKIP vs FAIL）。
 *
 * <p>码位分配：<br>
 * 5003 – 服务不可用（Python 未启动 / 配置关闭）→ smoke 可记 SKIP<br>
 * 5004 – 数据或契约问题（表头空 / 格式不支持 / Python 返回空）→ smoke 应计 FAIL
 */
public enum ExportFailureReason {
    /** Python 服务未响应 / 连接超时 → 可选依赖缺失。 */
    SERVICE_UNAVAILABLE(5003, "导出服务暂不可用，请确认 Python 导出服务已启用"),
    
    /** export.enabled=false (配置关闭) → 可选依赖缺失。 */
    DISABLED_BY_CONFIG(5003, "导出功能已禁用（export.enabled=false）"),
    
    /** 表头为空 → 数据缺陷。 */
    EMPTY_HEADERS(5004, "表头为空，无法生成导出文件"),
    
    /** 格式不支持 → 契约错误。 */
    UNSUPPORTED_FORMAT(5004, "不支持的导出格式，仅支持 xlsx / pdf"),
    
    /** Python 返回空内容 → Python 侧缺陷。 */
    EMPTY_CONTENT_FROM_PYTHON(5004, "导出服务返回空内容，请稍后重试");

    private final int code;
    private final String message;

    ExportFailureReason(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
