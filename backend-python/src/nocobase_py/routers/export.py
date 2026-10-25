"""PHASE10: 报表导出路由。

端点：
- POST /api/export/xlsx   生成 Excel 文件
- POST /api/export/pdf    生成 PDF 文件

调用方：Java 后端（`ExportFileService`）。
Java 侧已完成取数 / 权限 / 行级 ACL / 审计，此处只负责把数据渲染成文件。
本路由**不直连业务数据库**。

请求体：
    {
      "headers": ["姓名", "年龄"],
      "rows": [["张三", 28], ["李四", 32]],
      "title": "客户名单"
    }

响应：文件字节流（Content-Type 按格式设置）。
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import Response
from pydantic import BaseModel, Field

from nocobase_py.security import AuthUser, get_current_user
from nocobase_py.services import export_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/export", tags=["export"])

XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


class ExportRequest(BaseModel):
    """导出请求体。"""

    headers: list[str] = Field(default_factory=list, description="列名（有序）")
    rows: list[list[Any]] = Field(default_factory=list, description="二维数据行")
    title: str = Field(default="export", description="报表标题 / 工作表名")


@router.post("/xlsx")
async def export_xlsx(
    body: ExportRequest,
    user: AuthUser = Depends(get_current_user),
) -> Response:
    """生成 Excel（xlsx）文件。"""
    data = export_service.build_xlsx(body.headers, body.rows, body.title)
    filename = f"{body.title}.xlsx"
    # 中文文件名需 RFC 5987 编码，否则浏览器下载会乱码
    content_disposition = f"attachment; filename*=UTF-8''{_urlquote(filename)}"
    return Response(
        content=data,
        media_type=XLSX_MEDIA_TYPE,
        headers={"Content-Disposition": content_disposition},
    )


@router.post("/pdf")
async def export_pdf(
    body: ExportRequest,
    user: AuthUser = Depends(get_current_user),
) -> Response:
    """生成 PDF 文件（含中文支持）。"""
    data = export_service.build_pdf(body.headers, body.rows, body.title)
    filename = f"{body.title}.pdf"
    content_disposition = f"attachment; filename*=UTF-8''{_urlquote(filename)}"
    return Response(
        content=data,
        media_type="application/pdf",
        headers={"Content-Disposition": content_disposition},
    )


def _urlquote(value: str) -> str:
    """URL 编码（供 Content-Disposition 的 RFC 5987 使用）。"""
    from urllib.parse import quote

    return quote(value)
