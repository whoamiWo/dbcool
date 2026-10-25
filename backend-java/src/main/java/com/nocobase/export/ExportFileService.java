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

    /** 导出开关：false 时直接返回空，避免无谓的外部调用。 */
    @Value("${export.enabled:true}")
    private boolean enabled;

    /**
     * 生成导出文件。
     *
     * @param format xlsx 或 pdf（小写）
     * @param headers 列名（有序）
     * @param rows 二维数据
     * @param title 报表标题（同时作为文件名）
     * @return 文件字节流；不可用时返回 null（由调用方降级为提示）
     */
    public byte[] build(String format, List<String> headers,
                        List<List<Object>> rows, String title) {
        if (!enabled) {
            log.warn("[导出] export.enabled=false，跳过文件生成 (format={})", format);
            return null;
        }
        if (headers == null || headers.isEmpty()) {
            log.warn("[导出] 表头为空，拒绝生成 {}", format);
            return null;
        }
        String fmt = format == null ? "" : format.toLowerCase();
        if (!fmt.equals("xlsx") && !fmt.equals("pdf")) {
            log.warn("[导出] 不支持的格式：{}（仅支持 xlsx / pdf）", format);
            return null;
        }

        Map<String, Object> body = Map.of(
                "headers", headers,
                "rows", rows == null ? List.of() : rows,
                "title", title == null ? "export" : title
        );

        HttpHeaders headers0 = new HttpHeaders();
        headers0.setContentType(MediaType.APPLICATION_JSON);

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
                return null;
            }
            log.info("[导出] 生成成功 format={} bytes={} rows={}", fmt, data.length,
                    rows == null ? 0 : rows.size());
            return data;
        } catch (RestClientException e) {
            log.error("[导出] 调用 Python 导出服务失败 url={} format={} err={}",
                    pythonUrl, fmt, e.getMessage());
            return null;
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
