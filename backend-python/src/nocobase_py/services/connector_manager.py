"""连接器管理服务 (W4)。"""

import logging
from typing import Dict, Optional

from nocobase_py.services.connectors import (
    BaseConnector,
    ConnectorConfig,
    get_connector,
    list_connectors,
)
from nocobase_py.models.settings import Settings

logger = logging.getLogger(__name__)


class ConnectorManager:
    """连接器管理器，负责连接器的生命周期管理和消息分发。"""

    def __init__(self):
        self._connectors: Dict[str, BaseConnector] = {}
        self._settings: Optional[Settings] = None

    def set_settings(self, settings: Settings):
        """设置 Settings 实例（从数据库加载配置）。"""
        self._settings = settings

    def _load_config(self, name: str) -> Optional[ConnectorConfig]:
        """从 Settings 加载连接器配置。"""
        if not self._settings:
            return None

        try:
            config_dict = self._settings.get(f"connector.{name}.config", {})
            if not config_dict:
                return None

            config_dict["enabled"] = self._settings.get(
                f"connector.{name}.enabled", True
            )
            config_dict["timeout_seconds"] = self._settings.get(
                f"connector.{name}.timeout_seconds", 30
            )
            return ConnectorConfig(**config_dict)
        except Exception as e:
            logger.warning("[ConnectorManager] 加载 %s 配置失败：%s", name, e)
            return None

    async def get_or_create_connector(self, name: str) -> Optional[BaseConnector]:
        """获取或创建连接器实例。"""
        if name in self._connectors:
            return self._connectors[name]

        config = self._load_config(name)
        if not config or not config.enabled:
            return None

        try:
            connector = get_connector(name, config)
            self._connectors[name] = connector
            return connector
        except Exception as e:
            logger.error("[ConnectorManager] 创建 %s 连接器失败：%s", name, e)
            return None

    async def send_to_platform(
        self, platform: str, channel_id: str, message: str, **kwargs
    ) -> dict:
        """向指定平台发送消息。

        Args:
            platform: 平台名称 (slack/wecom/dingtalk/feishu/mattermost)
            channel_id: 频道 ID 或用户 ID
            message: 消息文本
            **kwargs: 额外参数

        Returns:
            发送结果 dict
        """
        connector = await self.get_or_create_connector(platform)
        if not connector:
            return {"ok": False, "error": "not_configured", "platform": platform}

        try:
            result = await connector.send_message(channel_id, message, **kwargs)
            result["platform"] = platform
            return result
        except Exception as e:
            logger.error("[ConnectorManager] 发送消息失败：%s", e)
            return {"ok": False, "error": str(e), "platform": platform}

    async def health_check_all(self) -> Dict[str, bool]:
        """对所有已配置的连接器进行健康检查。"""
        results = {}
        for name in list_connectors().keys():
            connector = await self.get_or_create_connector(name)
            if connector:
                try:
                    results[name] = await connector.health_check()
                except Exception as e:
                    logger.error(
                        "[ConnectorManager] %s 健康检查异常：%s", name, e
                    )
                    results[name] = False
            else:
                results[name] = False
        return results

    async def broadcast(
        self, message: str, platforms: list | None = None, **kwargs
    ) -> Dict[str, dict]:
        """广播消息到多个平台。

        Args:
            message: 消息文本
            platforms: 目标平台列表，None 表示所有已配置的平台
            **kwargs: 额外参数

        Returns:
            各平台的发送结果
        """
        if platforms is None:
            platforms = list(list_connectors().keys())

        results = {}
        for platform in platforms:
            connector = await self.get_or_create_connector(platform)
            if connector:
                try:
                    # 不同平台有不同的 channel_id 格式，这里简化处理
                    results[platform] = await connector.send_message(
                        kwargs.get(f"{platform}_channel", "general"), message, **kwargs
                    )
                except Exception as e:
                    results[platform] = {"ok": False, "error": str(e)}
            else:
                results[platform] = {"ok": False, "error": "not_configured"}

        return results


# 全局单例
connector_manager = ConnectorManager()
