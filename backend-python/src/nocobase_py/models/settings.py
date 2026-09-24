"""Settings 模型 — 内存版实现,支持 dot-notation 嵌套键访问。

<p>Phase52 收口遗留:PostgreSQL 表 settings + SQLAlchemy ORM 实现缺失。
本次端到端冒烟期间用内存 dict 简化实现,接口兼容 connector_manager。
未来接 DB 时只需替换 __init__/get/set 即可,接口保持稳定。
"""

from __future__ import annotations

from typing import Any


class Settings:
    """内存版 Settings:键路径支持 '.' 嵌套,默认空实例。

    兼容 Phase52 connector_manager 调用模式:
      - get("connector.wecom.config", {})  → dict 或 default
      - get("connector.wecom.enabled", True)
      - get("connector.wecom.timeout_seconds", 30)
    """

    def __init__(self, initial: dict[str, Any] | None = None) -> None:
        self._data: dict[str, Any] = {}
        if initial:
            for k, v in initial.items():
                self.set(k, v)

    def get(self, key: str, default: Any = None) -> Any:
        """嵌套键读取。key 用 '.' 分层(如 'connector.wecom.config')。

        Args:
            key: 点分隔路径
            default: 键不存在或值为空时返回

        Returns:
            键对应的值,或 default
        """
        parts = key.split(".")
        cur: Any = self._data
        for p in parts:
            if not isinstance(cur, dict) or p not in cur:
                return default
            cur = cur[p]
        # 空 dict/list 当作未配置 → 返 default(匹配 connector_manager 行为)
        if cur in (None, {}, [], ""):
            return default
        return cur

    def set(self, key: str, value: Any) -> None:
        """嵌套键写入(自动建中间 dict)。"""
        parts = key.split(".")
        cur = self._data
        for p in parts[:-1]:
            if p not in cur or not isinstance(cur[p], dict):
                cur[p] = {}
            cur = cur[p]
        cur[parts[-1]] = value

    def all(self) -> dict[str, Any]:
        """导出全量字典(用于调试/序列化)。"""
        return self._data

    def __repr__(self) -> str:
        return f"Settings({self._data})"