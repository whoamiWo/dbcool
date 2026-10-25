"""PHASE10: 报表导出服务 —— Excel / PDF 文件生成。

职责边界（架构约束）：
- 本服务**只接收数据**（headers + rows），不直连业务数据库；
  取数、权限过滤、行级 ACL、审计均由 Java 侧 `CollectionController` 完成。
- Java 通过内部 HTTP 调用 `/api/export/xlsx` 或 `/api/export/pdf`，
  本模块返回文件字节流（bytes），由 Java 透传为下载响应。

中文字体说明：
- PDF 使用 reportlab 内置 CJK 字体（STSong-Light，含 Adobe 标准中文字体）；
  无需外挂 .ttf 即可正确渲染简体中文，避免容器内缺字体导致乱码/方块。
"""

from __future__ import annotations

import io
import logging
from typing import Any

logger = logging.getLogger(__name__)

# Excel 单表最大行数（超出截断，防止超大导出撑爆内存）
MAX_ROWS = 100_000


def _normalize(headers: list[str], rows: list[list[Any]]) -> tuple[list[str], list[list[Any]]]:
    """归一化：表头转字符串；单元格 None → 空串、**保留原类型**（数字不转字符串）。

    保留原类型的原因：Excel 中数字必须以数值类型写入，否则单元格变成文本，
    无法排序/求和。PDF 渲染时才统一转字符串（见 _stringify_rows）。
    同时按 MAX_ROWS 截断，避免外部传入超大数据导致内存问题。
    """
    safe_headers = [str(h) if h is not None else "" for h in headers]
    safe_rows: list[list[Any]] = []
    for row in rows[:MAX_ROWS]:
        cells = row if isinstance(row, (list, tuple)) else [row]
        safe_rows.append(["" if c is None else c for c in cells])
    return safe_headers, safe_rows


def _stringify_rows(rows: list[list[Any]]) -> list[list[str]]:
    """PDF 渲染前把单元格统一转字符串（reportlab 表格不接受非字符串）。"""
    return [["" if c is None else str(c) for c in row] for row in rows]


def _cell_len(value: Any) -> int:
    """单元格显示长度（用于列宽估算）。"""
    return len(str(value)) if value is not None else 0


def build_xlsx(headers: list[str], rows: list[list[Any]], title: str = "export") -> bytes:
    """生成 xlsx 字节流。

    Args:
        headers: 列名列表
        rows: 二维数据（每行为单元格值列表）
        title: 工作表名（Excel sheet 名最长 31 字符，超长自动截断）

    Returns:
        xlsx 文件字节流
    """
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter

    safe_headers, safe_rows = _normalize(headers, rows)

    wb = Workbook()
    ws = wb.active
    # sheet 名最长 31 字符且不能含非法字符
    ws.title = (title or "export")[:31]

    # 表头：加粗 + 底色，首行冻结便于滚动查看
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill(start_color="1976D2", end_color="1976D2", fill_type="solid")
    for col_idx, name in enumerate(safe_headers, start=1):
        cell = ws.cell(row=1, column=col_idx, value=name)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = Alignment(horizontal="center", vertical="center")
    ws.freeze_panes = "A2"

    # 数据行
    for r_idx, row in enumerate(safe_rows, start=2):
        for c_idx, value in enumerate(row, start=1):
            ws.cell(row=r_idx, column=c_idx, value=value)

    # 列宽自适应（按内容最长单元格估算，上限 50 避免超宽列）
    for col_idx, name in enumerate(safe_headers, start=1):
        candidates = [_cell_len(name)]
        for row in safe_rows:
            if col_idx - 1 < len(row):
                candidates.append(_cell_len(row[col_idx - 1]))
        longest = max(candidates)
        ws.column_dimensions[get_column_letter(col_idx)].width = min(max(longest + 2, 8), 50)

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def build_pdf(headers: list[str], rows: list[list[Any]], title: str = "export") -> bytes:
    """生成 pdf 字节流（含中文支持）。

    使用 reportlabplatypus 的表格布局；中文字体走内置 STSong-Light。
    """
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.lib.units import mm
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

    safe_headers, safe_rows = _normalize(headers, rows)

    # 注册内置中文字体（CID 字体，无需外部 .ttf 文件）
    chinese_font = "STSong-Light"
    pdfmetrics.registerFont(UnicodeCIDFont(chinese_font))

    buf = io.BytesIO()
    # 列多时用横向页面，避免挤压
    page = landscape(A4) if len(safe_headers) > 6 else A4
    doc = SimpleDocTemplate(
        buf,
        pagesize=page,
        leftMargin=12 * mm,
        rightMargin=12 * mm,
        topMargin=14 * mm,
        bottomMargin=14 * mm,
    )

    styles: list[Any] = []

    # 标题
    from reportlab.lib.styles import ParagraphStyle

    title_style = ParagraphStyle(
        "ExportTitle",
        fontName=chinese_font,
        fontSize=16,
        leading=20,
        textColor=colors.HexColor("#1976D2"),
        spaceAfter=8,
    )
    styles.append(Paragraph(str(title or "导出报表"), title_style))
    styles.append(Spacer(1, 4 * mm))

    # 表格数据（表头 + 行）；PDF 需要字符串，此处统一转换
    table_data: list[list[Any]] = [safe_headers] + _stringify_rows(safe_rows)
    table = Table(table_data, repeatRows=1)  # repeatRows=1：翻页时重复表头
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1976D2")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("FONTNAME", (0, 0), (-1, -1), chinese_font),
                ("FONTSIZE", (0, 0), (-1, 0), 10),
                ("FONTSIZE", (0, 1), (-1, -1), 9),
                ("ALIGN", (0, 0), (-1, -1), "LEFT"),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#BDBDBD")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7FA")]),
                ("TOPPADDING", (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
            ]
        )
    )
    styles.append(table)

    doc.build(styles)
    return buf.getvalue()
