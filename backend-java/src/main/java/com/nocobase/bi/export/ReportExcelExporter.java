package com.nocobase.bi.export;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * BI 报表 Excel 导出器(Wave 4 C1)。
 *
 * <p>使用 SXSSF(流式写出)避免大报表 OOM:窗口 200 行,超出部分刷盘到临时文件。
 * 单元格值统一按 {@link #cellValue} 归一化,数值型写成 numeric 以便 Excel 端继续计算。
 *
 * <p>支持两种输入形态:
 * <ul>
 *   <li>透视矩阵:{@code rows/columns/values/data} — 展开成 行维度 × 列维度 交叉表</li>
 *   <li>扁平列表:直接列头 + 数据行</li>
 * </ul>
 */
@Component
public class ReportExcelExporter {

    private static final Logger log = LoggerFactory.getLogger(ReportExcelExporter.class);

    /** SXSSF 内存窗口行数:超过则刷入磁盘临时文件。 */
    private static final int ROW_WINDOW = 200;

    /** 透视矩阵导出为交叉表 xlsx。 */
    public byte[] exportPivot(Map<String, Object> pivotResult, String title) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(ROW_WINDOW);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.setCompressTempFiles(true);
            SXSSFSheet sheet = wb.createSheet(safeSheetName(title));

            CellStyle headerStyle = headerStyle(wb);

            @SuppressWarnings("unchecked")
            List<String> rowKeys = asStringList(pivotResult.get("rows"));
            @SuppressWarnings("unchecked")
            List<String> colKeys = asStringList(pivotResult.get("columns"));
            @SuppressWarnings("unchecked")
            List<String> measureFields = asStringList(pivotResult.get("values"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) pivotResult.getOrDefault("data", List.of());

            // 表头: [行维度] | col1|measure1 | col1|measure2 | ...
            Row head = sheet.createRow(0);
            int c = 0;
            head.createCell(c++).setCellValue("行");
            for (String ck : colKeys) {
                for (String mf : measureFields) {
                    Cell cell = head.createCell(c++);
                    cell.setCellValue(colKeys.size() > 1 ? ck + " · " + mf : mf);
                    cell.setCellStyle(headerStyle);
                }
            }
            head.getCell(0).setCellStyle(headerStyle);

            int r = 1;
            for (Map<String, Object> rec : data) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(str(rec.get("row")));
                int cc = 1;
                for (String ck : colKeys) {
                    for (String mf : measureFields) {
                        Object v = rec.get(ck + "|" + mf);
                        writeCell(row.createCell(cc++), v);
                    }
                }
            }

            sheet.trackAllColumnsForAutoSizing();
            wb.write(out);
            wb.dispose();
            log.info("[BI][export] pivot xlsx: {} 行 × {} 列", r - 1, c);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("透视表 Excel 导出失败: " + e.getMessage(), e);
        }
    }

    /** 扁平列表导出为 xlsx(列头取首行的 key 集合,保序)。 */
    public byte[] exportFlat(List<Map<String, Object>> records, String title) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(ROW_WINDOW);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.setCompressTempFiles(true);
            SXSSFSheet sheet = wb.createSheet(safeSheetName(title));
            CellStyle headerStyle = headerStyle(wb);

            Set<String> cols = new LinkedHashSet<>();
            for (Map<String, Object> rec : records) {
                if (rec != null) cols.addAll(rec.keySet());
            }
            List<String> headers = new ArrayList<>(cols);

            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int r = 1;
            for (Map<String, Object> rec : records) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < headers.size(); i++) {
                    writeCell(row.createCell(i), rec == null ? null : rec.get(headers.get(i)));
                }
            }

            sheet.trackAllColumnsForAutoSizing();
            wb.write(out);
            wb.dispose();
            log.info("[BI][export] flat xlsx: {} 行 × {} 列", r - 1, headers.size());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("列表 Excel 导出失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------

    private static void writeCell(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(v.toString());
        }
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    /** Excel sheet 名非法字符(: \ / ? * [ ])需替换,且长度上限 31。 */
    static String safeSheetName(String title) {
        String t = (title == null || title.isBlank()) ? "报表" : title;
        t = t.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return t.length() > 31 ? t.substring(0, 31) : t;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> res = new ArrayList<>();
        for (Object x : list) res.add(x == null ? "" : x.toString());
        return res;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 供测试断言:验证 XSSF 可回读(排除 SXSSF 临时文件依赖)。 */
    static Workbook readableCopy(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
    }
}
