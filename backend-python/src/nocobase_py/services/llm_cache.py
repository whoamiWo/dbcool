"""R11 LLM 响应缓存 — 相同 prompt + model 组合命中缓存,节省 API 调用成本."""

from __future__ import annotations

import hashlib
import json
import time
from typing import Any


class LLMCache:
    """LRU 内存缓存(带 TTL),用于 LLM 响应复用.

    Usage:
        cache = LLMCache(max_size=256, ttl_seconds=300)
        cached = cache.get(model="gpt-4", prompt="hello")
        if cached is not None:
            return cached
        response = await call_llm(...)
        cache.put(model="gpt-4", prompt="hello", response=response)
    """

    def __init__(self, max_size: int = 256, ttl_seconds: int = 300) -> None:
        self.max_size = max_size
        self.ttl_seconds = ttl_seconds
        self._store: dict[str, tuple[Any, float]] = {}
        # R11: hit/miss 统计,用于监控缓存效果 + 命中率告警
        self.hits: int = 0
        self.misses: int = 0

    def _key(self, model: str, prompt: str) -> str:
        raw = json.dumps({"model": model, "prompt": prompt}, sort_keys=True)
        return hashlib.sha256(raw.encode()).hexdigest()[:16]

    def get(self, model: str, prompt: str) -> Any | None:
        key = self._key(model, prompt)
        entry = self._store.get(key)
        if entry is None:
            self.misses += 1
            return None
        value, expires_at = entry
        if time.monotonic() > expires_at:
            del self._store[key]
            self.misses += 1
            return None
        self.hits += 1
        return value

    def put(self, model: str, prompt: str, response: Any) -> None:
        key = self._key(model, prompt)
        self._store[key] = (response, time.monotonic() + self.ttl_seconds)
        # LRU 淘汰:超过 max_size 时删除最早条目
        while len(self._store) > self.max_size:
            oldest = min(self._store, key=lambda k: self._store[k][1])
            del self._store[oldest]

    def stats(self) -> dict[str, Any]:
        total = self.hits + self.misses
        hit_rate = (self.hits / total) if total else 0.0
        return {
            "size": len(self._store),
            "max_size": self.max_size,
            "ttl_seconds": self.ttl_seconds,
            "hits": self.hits,
            "misses": self.misses,
            "hit_rate": round(hit_rate, 4),
        }

    def should_warn_low_hit_rate(self, threshold: float = 0.4, min_calls: int = 20) -> bool:
        """判断是否应该发出低命中率告警.

        Args:
            threshold: 命中率阈值,默认 < 40% 告警
            min_calls: 最少调用次数,避免冷启动误报

        Returns:
            是否需要告警
        """
        total = self.hits + self.misses
        if total < min_calls:
            return False
        return (self.hits / total) < threshold

    def clear(self) -> None:
        self._store.clear()
        self.hits = 0
        self.misses = 0
