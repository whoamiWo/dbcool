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

    # R11: LLM 三层防护可配参数(演示前可放宽灰度)
    llm_rate_limit_max_requests: int = 30
    llm_rate_limit_window_seconds: int = 60
    llm_cache_max_size: int = 256
    llm_cache_ttl_seconds: int = 300
    llm_daily_call_limit: int = 100
    llm_daily_token_limit: int = 50_000
    llm_monthly_call_limit: int = 2000
    llm_monthly_token_limit: int = 1_000_000

    # R15: 插件热加载目录
    plugins_dir: str = "plugins"


@lru_cache
def get_settings() -> Settings:
    """获取单例配置."""
    return Settings()
