"""R15 Python 端插件加载器(Java 端的轻量对等实现).

扫描 plugins_dir 下的 YAML Manifest 文件,加载并校验到 Registry.
支持单文件加载、目录扫描、已注册清单查询、失败跳过.

R15 增强: 插件生命周期钩子.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator, Protocol


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PluginManifest:
    """插件 manifest(不可变值对象)."""

    name: str
    version: str
    description: str = ""
    permissions: tuple[str, ...] = field(default_factory=tuple)
    requires_auth: bool = False
    source_path: str = ""


class PluginValidationError(ValueError):
    """manifest 校验失败."""


class PluginLifecycleHook(Protocol):
    """插件生命周期钩子(Protocol,不强制实现)."""

    def on_register(self, manifest: PluginManifest) -> None: ...

    def on_reregister(
        self, old: PluginManifest | None, new: PluginManifest
    ) -> None: ...

    def on_unregister(self, manifest: PluginManifest) -> None: ...


class PluginRegistry:
    """进程内插件注册表."""

    def __init__(self) -> None:
        self._store: dict[str, PluginManifest] = {}
        self._hooks: list[PluginLifecycleHook] = []

    # ── 钩子管理 ────────────────────────────────────────────
    def add_lifecycle_hook(self, hook: PluginLifecycleHook) -> None:
        if hook is not None and hook not in self._hooks:
            self._hooks.append(hook)
            logger.info(
                "注册生命周期钩子: %s",
                getattr(hook, "__class__", type(hook)).__name__,
            )

    def remove_lifecycle_hook(self, hook: PluginLifecycleHook) -> bool:
        if hook in self._hooks:
            self._hooks.remove(hook)
            return True
        return False

    def hook_count(self) -> int:
        return len(self._hooks)

    # ── 注册 / 重注册 / 注销 ──────────────────────────────
    def register(self, manifest: PluginManifest) -> None:
        if not manifest.name or not manifest.name.strip():
            raise PluginValidationError("name 必填")
        if not manifest.version or not manifest.version.strip():
            raise PluginValidationError("version 必填")
        if manifest.name in self._store:
            raise PluginValidationError(f"插件 '{manifest.name}' 已注册,不可重复")
        self._store[manifest.name] = manifest
        logger.info(
            "注册插件: %s v%s (perms=%s)",
            manifest.name,
            manifest.version,
            list(manifest.permissions),
        )
        self._fire("on_register", manifest)

    def reregister(self, manifest: PluginManifest) -> None:
        """重新注册同名插件(覆盖)."""
        if not manifest.name or not manifest.name.strip():
            raise PluginValidationError("name 必填")
        old = self._store.get(manifest.name)
        self._store[manifest.name] = manifest
        logger.info("更新插件: %s v%s", manifest.name, manifest.version)
        self._fire("on_reregister", old, manifest)

    def unregister(self, name: str) -> bool:
        removed = self._store.pop(name, None)
        if removed is not None:
            logger.info("注销插件: %s", name)
            self._fire("on_unregister", removed)
            return True
        return False

    def _fire(self, method: str, *args: Any) -> None:
        for h in list(self._hooks):
            try:
                fn = getattr(h, method, None)
                if fn is None:
                    continue
                fn(*args)
            except Exception:  # noqa: BLE001
                logger.warning(
                    "hook %s.%s 失败", type(h).__name__, method, exc_info=True
                )

    # ── 查询 ───────────────────────────────────────────────
    def get(self, name: str) -> PluginManifest | None:
        return self._store.get(name)

    def is_registered(self, name: str) -> bool:
        return name in self._store

    def registered_names(self) -> list[str]:
        return list(self._store.keys())

    def size(self) -> int:
        return len(self._store)

    def all(self) -> list[PluginManifest]:
        return list(self._store.values())

    def clear(self) -> None:
        for m in list(self._store.values()):
            self._fire("on_unregister", m)
        self._store.clear()


# ── YAML 简易解析(避免新增 PyYAML 依赖) ────────────────────────────
def _parse_simple_yaml(content: str) -> dict[str, str]:
    """极简 YAML 解析:仅支持 key: value 形式 + 嵌套列表,忽略空行与注释."""
    scalars: dict[str, str] = {}
    list_items: list[str] = []
    current_list_key: str | None = None
    for raw in content.splitlines():
        # 去注释
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("- "):
            # 列表项
            list_items.append(stripped[2:].strip())
            continue
        if ":" not in stripped:
            continue
        key, _, value = stripped.partition(":")
        key = key.strip()
        value = value.strip()
        # 把累积的列表写回 scalars
        if list_items:
            target_key = current_list_key or "permissions"
            scalars[target_key] = ",".join(list_items)
            list_items = []
            current_list_key = None
        if not value:
            # key 后跟一个列表
            current_list_key = key
        else:
            scalars[key] = value
            current_list_key = None
    # 文件末尾的列表
    if list_items and current_list_key:
        scalars[current_list_key] = ",".join(list_items)
    return scalars


def load_manifest_from_file(path: Path) -> PluginManifest:
    """从单文件加载 manifest.失败抛 PluginValidationError."""
    if not path.exists():
        raise PluginValidationError(f"manifest 文件不存在: {path}")
    text = path.read_text(encoding="utf-8")
    data = _parse_simple_yaml(text)
    name = data.get("name", "")
    version = data.get("version", "")
    if not name:
        raise PluginValidationError(f"{path}: name 必填")
    if not version:
        raise PluginValidationError(f"{path}: version 必填")
    perms_raw = data.get("permissions", "")
    permissions: tuple[str, ...] = tuple(
        p.strip() for p in re.split(r"[,\s]+", perms_raw) if p.strip()
    )
    requires_auth = data.get("requiresAuth", "false").lower() in ("1", "true", "yes")
    return PluginManifest(
        name=name,
        version=version,
        description=data.get("description", ""),
        permissions=permissions,
        requires_auth=requires_auth,
        source_path=str(path),
    )


def iter_manifest_files(plugins_dir: str | Path) -> Iterator[Path]:
    """迭代目录下所有 *.yaml / *.yml manifest 文件."""
    p = Path(plugins_dir)
    if not p.is_dir():
        return
    for ext in ("*.yaml", "*.yml"):
        yield from sorted(p.glob(ext))


def load_directory(plugins_dir: str | Path, registry: PluginRegistry) -> list[PluginManifest]:
    """加载目录下所有 manifest,跳过加载失败的(只 warn).返回成功加载的清单."""
    loaded: list[PluginManifest] = []
    for path in iter_manifest_files(plugins_dir):
        try:
            m = load_manifest_from_file(path)
            registry.register(m)
            loaded.append(m)
        except PluginValidationError as e:
            logger.warning("跳过无效 manifest %s: %s", path, e)
    return loaded
