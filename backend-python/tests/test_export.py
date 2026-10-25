"""PHASE10: 报表导出服务测试（Excel / PDF）。

验证：
1. xlsx 生成且表头/数据正确写入（用 openpyxl 回读校验，而非只断言不报错）
2. pdf 生成且为合法 PDF（魔数 %PDF）
3. 中文内容可正确写入（不乱码、不丢字）
4. None 单元格转空串，不抛异常
5. 空数据（无行）也能生成合法文件
"""

from __future__ import annotations

import io

import pytest

from nocobase_py.services import export_service


def test_build_xlsx_writes_headers_and_rows() -> None:
    """xlsx 表头与数据行必须正确写入（回读校验）。"""
    from openpyxl import load_workbook

    headers = ["姓名", "年龄", "城市"]
    rows = [["张三", 28, "北京"], ["李四", 32, "上海"]]

    data = export_service.build_xlsx(headers, rows, title="客户名单")

    assert data[:2] == b"PK", "xlsx 应为 zip 容器（PK 魔数）"
    wb = load_workbook(io.BytesIO(data))
    ws = wb.active
    assert ws.title == "客户名单"
    # 表头
    assert [c.value for c in ws[1]] == headers
    # 数据行
    assert [c.value for c in ws[2]] == ["张三", 28, "北京"]
    assert [c.value for c in ws[3]] == ["李四", 32, "上海"]


def test_build_pdf_is_valid_pdf() -> None:
    """pdf 必须以 %PDF 开头（合法 PDF 魔数）。"""
    data = export_service.build_pdf(["姓名", "部门"], [["张三", "研发"]], title="员工表")

    assert data[:4] == b"%PDF", "应为合法 PDF 文件"
    assert len(data) > 500, "PDF 不应是空壳"


def test_xlsx_handles_none_and_empty_rows() -> None:
    """None 单元格应转空串；无数据行时也能生成文件。"""
    from openpyxl import load_workbook

    data = export_service.build_xlsx(["a", "b"], [[None, "x"]], title="t")
    ws = load_workbook(io.BytesIO(data)).active
    # None -> 空串（openpyxl 读回为 None 或 ""，均可接受，只要不抛异常）
    first = ws.cell(row=2, column=1).value
    assert first is None or first == ""

    # 空行集合
    data2 = export_service.build_xlsx(["a"], [], title="empty")
    assert data2[:2] == b"PK"


def test_pdf_handles_none() -> None:
    """PDF 遇 None 不抛异常。"""
    data = export_service.build_pdf(["a", "b"], [[None, 1]], title="t")
    assert data[:4] == b"%PDF"


def test_title_truncated_for_xlsx_sheet_name() -> None:
    """Excel sheet 名最长 31 字符，超长标题必须截断而非报错。"""
    from openpyxl import load_workbook

    long_title = "超长标题" * 20  # 80 字符
    data = export_service.build_xlsx(["h"], [["v"]], title=long_title)
    ws = load_workbook(io.BytesIO(data)).active
    assert len(ws.title) <= 31


@pytest.mark.parametrize("fmt", ["xlsx", "pdf"])
def test_export_formats_support_chinese(fmt: str) -> None:
    """中文表头与内容在两种格式下均可用。"""
    headers = ["客户名称", "金额"]
    rows = [["北京科技有限公司", 12345.67]]
    if fmt == "xlsx":
        data = export_service.build_xlsx(headers, rows, title="中文报表")
        assert data[:2] == b"PK"
    else:
        data = export_service.build_pdf(headers, rows, title="中文报表")
        assert data[:4] == b"%PDF"
