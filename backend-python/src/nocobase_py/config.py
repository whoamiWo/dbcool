"""应用配置."""

import os
from functools import lru_cache

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """从环境变量加载配置."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # 服务
    app_name: str = "nocobase-py"
    app_version: str = "2.1.2"
    debug: bool = False

    # HTTP
    host: str = "0.0.0.0"
    port: int = 8000

    # Java 后端(内部调用)
    java_backend_url: str = "http://localhost:8080"

    # JWT(与 Java 共享密钥,Week 4 之后接真实校验)
    # PHASE114: 移除 dev 默认值 —— 未注入 JWT_SECRET 即启动失败(fail fast)，
    # 与 Java 侧 KeyRingService「dev 占位密钥不得用于生产」对齐。
    # 测试由 tests/conftest.py 显式注入 CI 专用密钥，不依赖任何公开默认值。
    jwt_secret: str = Field(
        min_length=32,
    )
    jwt_algorithm: str = "HS256"  # 保留兼容既有引用

    @model_validator(mode="after")
    def _guard_jwt_secret(self) -> "Settings":
        """PHASE114: 生产环境禁止使用开发占位密钥(与 Java KeyRingService 同语义)。

        允许保留 dev 占位值的环境：dev / test / ci profile，或 pytest 运行上下文。
        其余环境(app_env 未识别为测试)检测到占位密钥即抛错,避免公开密钥上线。
        """
        s = self.jwt_secret
        if "dev_jwt_secret" not in s:
            return self
        app_env = os.environ.get("APP_ENV", "").lower()
        in_pytest = bool(os.environ.get("PYTEST_CURRENT_TEST"))
        if app_env in ("dev", "test", "ci") or in_pytest:
            return self
        raise ValueError(
            "JWT_SECRET 为开发占位值('dev_jwt_secret...')，"
            "生产环境必须由环境变量注入 ≥32 字符的真实密钥"
        )
    jwt_algorithms: list[str] = ["HS256", "HS384", "HS512"]  # 支持 Java jjwt 自动选择的算法

    # CORS
    cors_origins: list[str] = [
        "http://localhost:5173",
        "http://localhost:3000",
        "http://localhost",
    ]

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_enabled: bool = True  # 关闭时限流/缓存/配额降级为单进程内存模式

    # R11: LLM 三层防护可配参数(演示前可放宽灰度)
    llm_rate_limit_max_requests: int = 30
    llm_rate_limit_window_seconds: int = 60
    llm_cache_max_size: int = 256
    llm_cache_ttl_seconds: int = 300
    llm_daily_call_limit: int = 100
    llm_daily_token_limit: int = 50_000
    llm_monthly_call_limit: int = 2000
    llm_monthly_token_limit: int = 1_000_000

    # LLM provider(OpenAI 兼容协议: OpenAI / DeepSeek / 通义 / 本地 Ollama 等)
    # 配置 llm_api_key + llm_base_url 后 /api/ai/chat 走真实模型;
    # 未配置时回退为 simulated 响应(便于无密钥环境联调)。
    llm_api_key: str = ""
    llm_base_url: str = ""
    llm_model: str = "gpt-4o"

    # PHASE 57: 服务间调用令牌(Java → Python 内部端点,如 /api/ai/embedding)。
    # 必须与 Java 侧 ai.internal-token 配置一致;未配置时内部端点仅接受用户 JWT。
    internal_service_token: str = ""

    # Slack 连接器配置
    slack_signing_secret: str = ""
    slack_bot_token: str = ""
    slack_team_id: str = ""

    # 企业微信连接器配置
    wecom_corp_id: str = ""
    wecom_corp_secret: str = ""
    wecom_agent_id: str = ""

    # 钉钉连接器配置
    dingtalk_app_key: str = ""
    dingtalk_app_secret: str = ""
    dingtalk_agent_id: str = ""

    # 飞书连接器配置
    feishu_app_id: str = ""
    feishu_app_secret: str = ""

    # Mattermost 连接器配置
    mattermost_webhook_url: str = ""
    mattermost_bot_token: str = ""
    mattermost_server_url: str = ""  # Bot API 基础路径，如 https://mattermost.example.com/api/v4
    mattermost_webhook_token: str = ""  # outgoing webhook token(P0-2a 入站校验)

    # Slack 入站事件转发目标频道（Java IM channel UUID）
    slack_event_channel_id: str = ""

    # R15: 插件热加载目录
    plugins_dir: str = "plugins"


@lru_cache
def get_settings() -> Settings:
    """获取单例配置."""
    return Settings()
