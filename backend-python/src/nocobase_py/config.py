"""应用配置."""

from functools import lru_cache

from pydantic import Field
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
    app_version: str = "0.0.1"
    debug: bool = False

    # HTTP
    host: str = "0.0.0.0"
    port: int = 8000

    # Java 后端(内部调用)
    java_backend_url: str = "http://localhost:8080"

    # JWT(与 Java 共享密钥,Week 4 之后接真实校验)
    jwt_secret: str = Field(
        default="dev_jwt_secret_at_least_32_characters_long_for_hs256",
        min_length=32,
    )
    jwt_algorithm: str = "HS256"

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
