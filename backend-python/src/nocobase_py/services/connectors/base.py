"""连接器抽象基类与注册表 (W4)。"""

from abc import ABC, abstractmethod
from typing import Any, Dict, Type
from pydantic import BaseModel, ConfigDict


class ConnectorConfig(BaseModel):
    """连接器配置基类 — 允许额外字段以兼容各平台子类的专属配置。

    端到端冒烟期间发现 router 调用 SlackConnector(signing_secret=..., bot_token=...)
    这种 keyword 风格,但子类 __init__ 签名是 (self, config: ConnectorConfig)。
    修复:ConfigDict(extra='allow') 让 Pydantic v2 接受所有平台专属字段,
    getattr(config, 'app_id', '') / getattr(config, 'bot_token', '') 全部命中。
    """
    model_config = ConfigDict(extra='allow')
    enabled: bool = True
    timeout_seconds: int = 30


class BaseConnector(ABC):
    """外部平台连接器抽象基类。"""

    NAME: str = "base"  # 子类覆盖
    DISPLAY_NAME: str = "Base Connector"

    def __init__(self, config: ConnectorConfig):
        self.config = config

    @abstractmethod
    async def health_check(self) -> bool:
        """健康检查。"""
        pass

    @abstractmethod
    async def authenticate(self) -> bool:
        """认证测试。"""
        pass

    async def send_message(self, channel_id: str, message: dict) -> bool:
        """发送消息（可选实现）。"""
        raise NotImplementedError("该连接器不支持发送消息")

    async def receive_events(self) -> list:
        """接收入站事件（可选实现）。"""
        raise NotImplementedError("该连接器不支持接收事件")


_connector_registry: Dict[str, Type[BaseConnector]] = {}


def register_connector(connector_class: Type[BaseConnector]):
    """注册连接器类。"""
    _connector_registry[connector_class.NAME] = connector_class
    return connector_class


def get_connector(name: str, config: ConnectorConfig) -> BaseConnector:
    """获取连接器实例。"""
    if name not in _connector_registry:
        raise ValueError(f"未知连接器：{name}")
    return _connector_registry[name](config)


def list_connectors() -> Dict[str, dict]:
    """列出所有已注册的连接器。"""
    return {
        name: {
            "name": cls.NAME,
            "display_name": cls.DISPLAY_NAME,
        }
        for name, cls in _connector_registry.items()
    }
