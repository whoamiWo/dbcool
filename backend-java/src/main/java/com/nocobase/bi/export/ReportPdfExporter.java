package com.nocobase.bi.export;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BI 报表 PDF 导出器(Wave 4 C1)。
 *
 * <p>使用 iText7 生成 A4 横向报表,自动回退系统字体渲染中文。
 * 透视矩阵展开成交叉表,扁平列表直接列头 + 数据行。
 */
@Component
public class ReportPdfExporter {

    private static final Logger log = LoggerFactory.getLogger(ReportPdfExporter.class);

    /** 透视矩阵导出为 pdf(横向 A4)。 */
    public byte[] exportPivot(Map<String, Object> pivotResult, String title) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4.rotate());

            // 标题
            doc.add(new com.itextpdf.layout.element.Paragraph(title == null ? "报表" : title)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontSize(16)
                    .setFontColor(new DeviceRgb(50, 50, 50)));

            @SuppressWarnings("unchecked")
            List<String> rowKeys = asStringList(pivotResult.get("rows"));
            @SuppressWarnings("unchecked")
            List<String> colKeys = asStringList(pivotResult.get("columns"));
            @SuppressWarnings("unchecked")
            List<String> measureFields = asStringList(pivotResult.get("values"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) pivotResult.getOrDefault("data", List.of());

            int totalCols = 1 + colKeys.size() * measureFields.size();
            Table table = new Table(UnitValue.createPercentArray(totalCols));

            // 表头
            addHeader(table, rowKeys, colKeys, measureFields);

            // 数据行
            for (Map<String, Object> rec : data) {
                table.addCell(str(rec.get("row")));
                for (String ck : colKeys) {
                    for (String mf : measureFields) {
                        Object v = rec.get(ck + "|" + mf);
                        table.addCell(formatCell(v));
                    }
                }
            }

            doc.add(table);
            doc.close();
            log.info("[BI][export] pivot pdf: {} 行 × {} 列", data.size(), totalCols);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("透视表 PDF 导出失败：" + e.getMessage(), e);
        }
    }

    /** 扁平列表导出为 pdf。 */
    public byte[] exportFlat(List<Map<String, Object>> records, String title) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf, PageSize.A4.rotate());

            doc.add(new com.itextpdf.layout.element.Paragraph(title == null ? "报表" : title)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontSize(16)
                    .setFontColor(new DeviceRgb(50, 50, 50)));

            Set<String> cols = new LinkedHashSet<>();
            for (Map<String, Object> rec : records) {
                if (rec != null) cols.addAll(rec.keySet());
            }
            List<String> headers = new ArrayList<>(cols);

            Table table = new Table(UnitValue.createPercentArray(headers.size()));
            for (String h : headers) table.addCell(h);
            for (Map<String, Object> rec : records) {
                for (String h : headers) table.addCell(formatCell(rec == null ? null : rec.get(h)));
            }

            doc.add(table);
            doc.close();
            log.info("[BI][export] flat pdf: {} 行 × {} 列", records.size(), headers.size());
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("列表 PDF 导出失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------

    private void addHeader(Table table, List<String> rowKeys, List<String> colKeys, List<String> measureFields) {
        table.addCell("行");
        for (String ck : colKeys) {
            for (String mf : measureFields) {
                String label = colKeys.size() > 1 ? ck + " · " + mf : mf;
                table.addCell(label);
            }
        }
    }

    private String formatCell(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            // 保留两位小数
            return String.format("%.2f", n.doubleValue());
        }
        return v.toString();
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> res = new ArrayList<>();
        for (Object x : list) res.add(x == null ? "" : x.toString());
        return res;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
