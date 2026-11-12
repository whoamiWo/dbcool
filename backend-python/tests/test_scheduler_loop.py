"""SIGSEGV 根因回归测试 —— 后台调度线程的事件循环纪律.

背景：scheduler 后台线程内直接 asyncio.run() 会新建并销毁一个临时 uvloop，
共享 async Redis 客户端绑定到已销毁的循环 → 主循环使用时报
"Event loop is closed"（/api/health/ready 503），并最终触发进程级
SIGSEGV（exit=139）。修复：
1. scheduler.run_coro() 把协程提交到应用主事件循环执行；
2. redis_client.get_redis() 增加循环亲和性守卫，绑定失效时重建客户端。
"""

from __future__ import annotations

import asyncio
import threading
from types import SimpleNamespace

import pytest

import nocobase_py.redis_client as rc
from nocobase_py.services.scheduler import run_coro, set_main_loop


class _FakeRedis:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.closed = False

    async def ping(self):
        return True

    async def aclose(self):
        self.closed = True

    async def close(self):
        self.closed = True


@pytest.fixture
def fake_redis(monkeypatch):
    created = []

    def _factory(**kwargs):
        client = _FakeRedis(**kwargs)
        created.append(client)
        return client

    monkeypatch.setattr(rc.redis, "Redis", _factory)
    monkeypatch.setattr(
        rc,
        "get_settings",
        lambda: SimpleNamespace(redis_enabled=True, redis_host="h", redis_port=6379),
    )
    rc._redis = None
    rc._redis_loop = None
    yield created
    rc._redis = None
    rc._redis_loop = None


class TestSchedulerLoopDiscipline:
    @pytest.mark.asyncio
    async def test_run_coro_executes_on_registered_main_loop(self):
        main_loop = asyncio.get_running_loop()
        set_main_loop(main_loop)
        try:

            def worker():
                async def probe():
                    return asyncio.get_running_loop() is main_loop

                return run_coro(probe())

            ok = await main_loop.run_in_executor(None, worker)
            assert ok is True, "run_coro 必须在主事件循环上执行协程"
        finally:
            set_main_loop(None)

    def test_run_coro_falls_back_without_main_loop(self):
        """脚本/测试环境（无主循环）时 run_coro 退化为独立循环，仍可用。"""
        set_main_loop(None)
        try:
            assert run_coro(asyncio.sleep(0, result=42)) == 42
        finally:
            set_main_loop(None)

    @pytest.mark.asyncio
    async def test_redis_client_reuse_same_loop(self, fake_redis):
        c1 = await rc.get_redis()
        c2 = await rc.get_redis()
        assert c1 is c2, "同一事件循环内必须复用同一客户端"
        assert len(fake_redis) == 1

    @pytest.mark.asyncio
    async def test_redis_client_rebuilt_after_creator_loop_closed(self, fake_redis):
        """客户端绑定的循环已关闭（临时循环销毁）时必须重建，禁止带病复用。"""

        def create_on_temp_loop():
            # 模拟后台线程 asyncio.run()：在另一个线程新建并销毁临时循环
            loop = asyncio.new_event_loop()
            try:
                return loop.run_until_complete(rc.get_redis())
            finally:
                loop.close()

        stale = await asyncio.get_running_loop().run_in_executor(None, create_on_temp_loop)

        fresh = await rc.get_redis()
        assert fresh is not stale, "绑定已关闭循环的客户端必须被重建"
        assert stale.closed is True, "旧客户端必须被关闭"
        assert rc._redis_loop is asyncio.get_running_loop()
        # 重建后在主循环上可正常复用
        assert await rc.get_redis() is fresh
