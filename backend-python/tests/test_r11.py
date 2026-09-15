"""R11 LLM 成本控制 — 限流 + 缓存 + 配额测试."""

import time

import pytest
from fastapi.testclient import TestClient

from nocobase_py.main import create_app
from nocobase_py.middleware.rate_limit import SlidingWindowRateLimiter
from nocobase_py.services.llm_cache import LLMCache
from nocobase_py.services.quota import QuotaService


@pytest.fixture
def client():
    app = create_app()
    with TestClient(app) as c:
        yield c


class TestSlidingWindowRateLimiter:
    def test_consume_within_limit(self):
        limiter = SlidingWindowRateLimiter(max_requests=3, window_seconds=60)
        limiter.consume("u1")
        limiter.consume("u1")
        limiter.consume("u1")
        assert limiter.remaining("u1") == 0

    def test_consume_exceeds_limit_raises(self):
        limiter = SlidingWindowRateLimiter(max_requests=2, window_seconds=60)
        limiter.consume("u1")
        limiter.consume("u1")
        with pytest.raises(Exception) as exc:
            limiter.consume("u1")
        assert "429" in str(exc.value) or "频繁" in str(exc.value)

    def test_isolated_between_users(self):
        limiter = SlidingWindowRateLimiter(max_requests=1, window_seconds=60)
        limiter.consume("u1")
        limiter.consume("u2")
        assert limiter.remaining("u2") == 0
        assert limiter.remaining("u1") == 0

    def test_reset_clears_state(self):
        limiter = SlidingWindowRateLimiter(max_requests=2, window_seconds=60)
        limiter.consume("u1")
        limiter.consume("u1")
        limiter.reset("u1")
        assert limiter.remaining("u1") == 2


class TestLLMCache:
    def test_put_and_get(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        cache.put("gpt-4", "hello", "world")
        assert cache.get("gpt-4", "hello") == "world"

    def test_get_miss(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        assert cache.get("gpt-4", "hello") is None

    def test_different_prompts_independent(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        cache.put("gpt-4", "a", "res_a")
        cache.put("gpt-4", "b", "res_b")
        assert cache.get("gpt-4", "a") == "res_a"
        assert cache.get("gpt-4", "b") == "res_b"
        assert cache.get("gpt-3.5", "a") is None

    def test_different_model_independent(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        cache.put("gpt-4", "hello", "res_4")
        cache.put("gpt-3.5", "hello", "res_35")
        assert cache.get("gpt-4", "hello") == "res_4"
        assert cache.get("gpt-3.5", "hello") == "res_35"

    def test_expired_entry_returns_none(self):
        cache = LLMCache(max_size=10, ttl_seconds=0)
        cache.put("m", "p", "v")
        time.sleep(0.01)
        assert cache.get("m", "p") is None

    def test_lru_eviction(self):
        cache = LLMCache(max_size=3, ttl_seconds=60)
        cache.put("m", "1", "v1")
        cache.put("m", "2", "v2")
        cache.put("m", "3", "v3")
        cache.put("m", "4", "v4")
        assert cache.get("m", "1") is None
        assert cache.get("m", "4") == "v4"

    def test_stats(self):
        cache = LLMCache(max_size=5, ttl_seconds=30)
        cache.put("m", "1", "v")
        s = cache.stats()
        assert s["size"] == 1
        assert s["max_size"] == 5
        assert s["ttl_seconds"] == 30

    def test_clear(self):
        cache = LLMCache(max_size=5, ttl_seconds=60)
        cache.put("m", "p", "v")
        cache.clear()
        assert cache.get("m", "p") is None


class TestQuotaService:
    def test_consume_within_daily_limit(self):
        quota = QuotaService(daily_call_limit=10, daily_token_limit=1000)
        quota.consume("u1", tokens=50)
        assert quota.remaining("u1")["daily_calls_remaining"] == 9
        assert quota.remaining("u1")["daily_tokens_remaining"] == 950

    def test_exceed_daily_call_limit_raises(self):
        quota = QuotaService(daily_call_limit=2, daily_token_limit=1000)
        quota.consume("u1")
        quota.consume("u1")
        with pytest.raises(ValueError) as exc:
            quota.consume("u1")
        assert "每日调用" in str(exc.value)

    def test_exceed_daily_token_limit_raises(self):
        quota = QuotaService(daily_call_limit=100, daily_token_limit=100)
        quota.consume("u1", tokens=50)
        with pytest.raises(ValueError) as exc:
            quota.consume("u1", tokens=60)
        assert "每日 token" in str(exc.value)

    def test_daily_and_monthly_are_independent(self):
        quota = QuotaService(daily_call_limit=2, monthly_call_limit=10)
        quota.consume("u1")
        quota.consume("u1")
        with pytest.raises(ValueError):
            quota.consume("u1")
        remaining = quota.remaining("u1")
        assert remaining["monthly_calls_remaining"] == 8

    def test_user_isolation(self):
        quota = QuotaService(daily_call_limit=1, daily_token_limit=100)
        quota.consume("u1")
        with pytest.raises(ValueError):
            quota.consume("u1")
        quota.consume("u2")
        assert quota.remaining("u2")["daily_calls_remaining"] == 0

    def test_remaining_returns_all_keys(self):
        quota = QuotaService()
        r = quota.remaining("u1")
        for key in (
            "daily_calls_remaining",
            "daily_tokens_remaining",
            "monthly_calls_remaining",
            "monthly_tokens_remaining",
        ):
            assert key in r


class TestAIAPIs:
    def test_chat_returns_response(self, client):
        resp = client.post(
            "/api/ai/chat", json={"model": "gpt-4", "prompt": "hello"}
        )
        assert resp.status_code == 200
        data = resp.json()
        assert "response" in data
        assert data["cached"] is False

    def test_chat_cache_hit(self, client):
        body = {"model": "gpt-4", "prompt": "cache me if you can"}
        resp1 = client.post("/api/ai/chat", json=body)
        assert resp1.status_code == 200
        resp2 = client.post("/api/ai/chat", json=body)
        assert resp2.json()["cached"] is True

    def test_quota_endpoint(self, client):
        resp = client.get("/api/ai/quota")
        assert resp.status_code == 200
        assert "remaining" in resp.json()

    def test_cache_stats_endpoint(self, client):
        resp = client.get("/api/ai/cache/stats")
        assert resp.status_code == 200
        assert "size" in resp.json()

    def test_chat_includes_quota_info(self, client):
        resp = client.post(
            "/api/ai/chat", json={"model": "gpt-4", "prompt": "test"}
        )
        assert resp.status_code == 200
        assert "quota" in resp.json()
        assert "daily_calls_remaining" in resp.json()["quota"]


# ── R11 增强: 告警 webhook 推送 ──────────────────────────────────
class TestWebhook:
    def test_disabled_pusher_noop(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher
        p = WebhookPusher(WebhookConfig(enabled=False))
        assert p.push({"kind": "x"}) is False
        assert p.stats()["configured"] == 0

    def test_feishu_format(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher
        calls = []

        def fake(url, payload, timeout):
            calls.append(payload)
            return True

        p = WebhookPusher(
            WebhookConfig(url="http://h", kind="feishu", enabled=True),
            post_fn=fake,
        )
        p.push({"kind": "rate_limit_exceeded", "user_id": "u", "detail": {}})
        assert calls[0]["msg_type"] == "text"
        assert "rate_limit_exceeded" in calls[0]["content"]["text"]

    def test_slack_format(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher
        calls = []

        def fake(url, payload, timeout):
            calls.append(payload)
            return True

        p = WebhookPusher(
            WebhookConfig(url="http://h", kind="slack", enabled=True), post_fn=fake
        )
        p.push({"kind": "q", "user_id": "u", "detail": {}})
        assert "text" in calls[0]

    def test_generic_passthrough(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher
        calls = []

        def fake(url, payload, timeout):
            calls.append(payload)
            return True

        p = WebhookPusher(
            WebhookConfig(url="http://h", kind="generic", enabled=True), post_fn=fake
        )
        ev = {"kind": "test", "user_id": "u3", "detail": {"foo": "bar"}}
        p.push(ev)
        assert calls[0] == ev

    def test_retry_until_success(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher
        attempts = []

        def flaky(url, payload, timeout):
            attempts.append(1)
            return len(attempts) >= 3

        p = WebhookPusher(
            WebhookConfig(url="http://h", enabled=True, max_retries=3),
            post_fn=flaky,
        )
        assert p.push({"kind": "t"}) is True
        assert len(attempts) == 3
        assert p.stats()["success"] == 1

    def test_failure_tracked(self):
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher

        def fail(url, payload, timeout):
            return False

        p = WebhookPusher(
            WebhookConfig(url="http://h", enabled=True, max_retries=2), post_fn=fail
        )
        assert p.push({"kind": "t"}) is False
        assert p.stats()["failure"] == 1

    def test_install_pusher_hook(self):
        from nocobase_py.services.alerts import get_collector
        from nocobase_py.services.webhook import (
            WebhookConfig,
            WebhookPusher,
            install_pusher,
            uninstall_pusher,
        )

        calls = []

        def fake(url, payload, timeout):
            calls.append(payload)
            return True

        install_pusher(
            WebhookPusher(
                WebhookConfig(url="http://hook", enabled=True), post_fn=fake
            )
        )
        try:
            get_collector().emit(
                "rate_limit_exceeded", user_id="hook-test", detail={"limit": 30}
            )
            assert len(calls) >= 1
        finally:
            uninstall_pusher()


# ── LLMCache hit_rate 告警 ────────────────────────────────────────
class TestLLMCacheHitRateWarning:
    def test_cold_start_no_warning(self):
        from nocobase_py.services.llm_cache import LLMCache

        c = LLMCache()
        for i in range(5):
            c.get("m", f"p{i}")
        assert c.should_warn_low_hit_rate() is False

    def test_all_miss_triggers_warning(self):
        from nocobase_py.services.llm_cache import LLMCache

        c = LLMCache()
        for i in range(25):
            c.get("m", f"p{i}")
        assert c.should_warn_low_hit_rate() is True

    def test_high_hit_rate_no_warning(self):
        from nocobase_py.services.llm_cache import LLMCache

        c = LLMCache()
        c.put("m", "p1", "v1")
        for _ in range(80):
            c.get("m", "p1")
        for _ in range(20):
            c.get("m", f"unique-{_}")
        assert c.should_warn_low_hit_rate() is False

    def test_custom_threshold(self):
        from nocobase_py.services.llm_cache import LLMCache

        c = LLMCache()
        for _ in range(10):
            c.put("m", "p", "v")
            c.get("m", "p")
        for _ in range(90):
            c.get("m", f"u{_}")
        # hits=10, misses=90
        assert c.should_warn_low_hit_rate(threshold=0.2, min_calls=20) is True
        assert c.should_warn_low_hit_rate(threshold=0.05, min_calls=20) is False


# ── plugin_loader 端到端 ───────────────────────────────────────────
class TestPluginLoader:
    def test_load_directory_with_real_files(self, tmp_path):
        from nocobase_py.services.plugin_loader import (
            PluginRegistry,
            load_directory,
        )

        (tmp_path / "p1.yaml").write_text("name: p1\nversion: 1.0\n", encoding="utf-8")
        (tmp_path / "p2.yml").write_text(
            "name: p2\nversion: 2.0\npermissions:\n  - read\n  - write\n",
            encoding="utf-8",
        )
        (tmp_path / "README.md").write_text("# readme")

        registry = PluginRegistry()
        loaded = load_directory(tmp_path, registry)
        assert len(loaded) == 2
        assert registry.is_registered("p1")
        assert registry.is_registered("p2")
        assert registry.get("p2").permissions == ("read", "write")

    def test_load_directory_skips_invalid(self, tmp_path):
        from nocobase_py.services.plugin_loader import (
            PluginRegistry,
            load_directory,
        )

        (tmp_path / "bad.yaml").write_text("version: 1.0\n", encoding="utf-8")  # 缺 name
        (tmp_path / "ok.yaml").write_text("name: ok\nversion: 1.0\n", encoding="utf-8")

        registry = PluginRegistry()
        loaded = load_directory(tmp_path, registry)
        assert len(loaded) == 1
        assert registry.is_registered("ok")

    def test_load_directory_missing_dir(self):
        from pathlib import Path

        from nocobase_py.services.plugin_loader import (
            PluginRegistry,
            load_directory,
        )

        registry = PluginRegistry()
        loaded = load_directory(Path("/tmp/definitely-not-exist-xyz"), registry)
        assert loaded == []
