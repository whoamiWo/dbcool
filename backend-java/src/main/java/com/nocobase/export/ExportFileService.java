package com.nocobase.export;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE10: 报表文件导出编排（Excel / PDF）。
 *
 * <p><b>职责边界</b>：
 * <ul>
 *   <li>Java 侧负责：取数、权限（ACL）、字段级过滤、行级过滤、审计 —— 与
 *       {@code CollectionController#exportCsv} 完全一致的编排；</li>
 *   <li>Python 侧负责：把数据渲染成 xlsx / pdf 字节流（openpyxl / reportlab）；</li>
 *   <li>本服务只做「把 headers+rows 交给 Python，拿回 byte[]」。</li>
 * </ul>
 *
 * <p>为何绕 Python：架构约定 Python 不直连业务库，而导出库在 Python 生态最成熟；
 * Java 不引入 POI/itext，保持依赖精简。
 */
@Service
public class ExportFileService {

    private static final Logger log = LoggerFactory.getLogger(ExportFileService.class);

    private final RestTemplate rest = new RestTemplate();

    /** Python 网关地址（与 docker-compose PYTHON_PORT / ai.python-url 同约定）。 */
    @Value("${export.python-url:${ai.python-url:http://localhost:8000}}")
    private String pythonUrl;

    /**
     * 服务间调用令牌（Python 侧 {@code internal_service_token}）。
     *
     * <p>取值优先级：{@code export.internal-token} → {@code INTERNAL_SERVICE_TOKEN}
     * → {@code AI_INTERNAL_TOKEN} → {@code EXPORT_INTERNAL_TOKEN}。
     * 必须包含 {@code INTERNAL_SERVICE_TOKEN}：Python 侧 SDK 与 CI/docker-compose 都用该变量名，
     * 原配置只读 AI_INTERNAL_TOKEN/EXPORT_INTERNAL_TOKEN，导致 CI 中 Java 不带 Authorization
     * → Python 返回 401 → 导出链路被误判为"服务不可用"而 SKIP（实测于 CI regression job）。
     */
    @Value("${export.internal-token:${INTERNAL_SERVICE_TOKEN:${AI_INTERNAL_TOKEN:${EXPORT_INTERNAL_TOKEN:}}}}")
    private String internalToken;

    /** 导出开关：false 时直接返回空，避免无谓的外部调用。 */
    @Value("${export.enabled:true}")
    private boolean enabled;

    /**
     * 导出结果封装（替代原来的简单 byte[] 返回值）。
     */
    public static class Result {
        private final byte[] data;
        private final ExportFailureReason reason;

        private Result(byte[] data, ExportFailureReason reason) {
            this.data = data;
            this.reason = reason;
        }

        public static Result ok(byte[] data) { return new Result(data, null); }
        public static Result fail(ExportFailureReason reason) { return new Result(null, reason); }

        public byte[] data() { return data; }
        public ExportFailureReason reason() { return reason; }
        public boolean isSuccess() { return reason == null; }
    }

    /**
     * 生成导出文件。
     *
     * @param format xlsx 或 pdf（小写）
     * @param headers 列名（有序）
     * @param rows 二维数据
     * @param title 报表标题（同时作为文件名）
     * @return 导出结果（成功时有 data，失败时有 reason）
     */
    public Result build(String format, List<String> headers,
                        List<List<Object>> rows, String title) {
        if (!enabled) {
            log.warn("[导出] export.enabled=false，跳过文件生成 (format={})", format);
            return Result.fail(ExportFailureReason.DISABLED_BY_CONFIG);
        }
        if (headers == null || headers.isEmpty()) {
            log.warn("[导出] 表头为空，拒绝生成 {}", format);
            return Result.fail(ExportFailureReason.EMPTY_HEADERS);
        }
        String fmt = format == null ? "" : format.toLowerCase();
        if (!fmt.equals("xlsx") && !fmt.equals("pdf")) {
            log.warn("[导出] 不支持的格式：{}（仅支持 xlsx / pdf）", format);
            return Result.fail(ExportFailureReason.UNSUPPORTED_FORMAT);
        }

        Map<String, Object> body = Map.of(
                "headers", headers,
                "rows", rows == null ? List.of() : rows,
                "title", title == null ? "export" : title
        );

        HttpHeaders headers0 = new HttpHeaders();
        headers0.setContentType(MediaType.APPLICATION_JSON);
        // Python 侧 /api/export/* 需要鉴权：优先携带服务间令牌，避免 401
        if (internalToken != null && !internalToken.isBlank()) {
            headers0.set(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken);
        }

        try {
            ResponseEntity<byte[]> resp = rest.exchange(
                    pythonUrl + "/api/export/" + fmt,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers0),
                    byte[].class
            );
            byte[] data = resp.getBody();
            if (data == null || data.length == 0) {
                log.error("[导出] Python 返回空内容 (format={})", fmt);
                return Result.fail(ExportFailureReason.EMPTY_CONTENT_FROM_PYTHON);
            }
            log.info("[导出] 生成成功 format={} bytes={} rows={}", fmt, data.length,
                    rows == null ? 0 : rows.size());
            return Result.ok(data);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // 连不上 / 超时：Python 服务未就绪 —— 这才是真正的"可选依赖缺失"
            log.error("[导出] 无法连接 Python 导出服务 url={} format={} err={}",
                    pythonUrl, fmt, e.getMessage());
            return Result.fail(ExportFailureReason.SERVICE_UNAVAILABLE);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 4xx：401/403 是令牌配置不一致，其它 4xx 是请求契约问题，均属真实缺陷
            int sc = e.getStatusCode().value();
            log.error("[导出] Python 返回 {} url={} format={} body={}",
                    sc, pythonUrl, fmt, e.getResponseBodyAsString());
            return Result.fail(sc == 401 || sc == 403
                    ? ExportFailureReason.UPSTREAM_AUTH_FAILED
                    : ExportFailureReason.UPSTREAM_REJECTED);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // 5xx：Python 侧内部缺陷
            log.error("[导出] Python 内部错误 {} url={} format={} body={}",
                    e.getStatusCode().value(), pythonUrl, fmt, e.getResponseBodyAsString());
            return Result.fail(ExportFailureReason.UPSTREAM_ERROR);
        } catch (RestClientException e) {
            log.error("[导出] 调用 Python 导出服务失败 url={} format={} err={}",
                    pythonUrl, fmt, e.getMessage());
            return Result.fail(ExportFailureReason.SERVICE_UNAVAILABLE);
        }
    }

    /** 媒体类型（供响应头使用）。 */
    public static String mediaTypeOf(String format) {
        return "pdf".equalsIgnoreCase(format)
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    /** 文件扩展名。 */
    public static String extensionOf(String format) {
        return "pdf".equalsIgnoreCase(format) ? ".pdf" : ".xlsx";
    }
}
