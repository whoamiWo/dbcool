"""外部连接器包。"""

from nocobase_py.services.connectors.base import (
    BaseConnector,
    ConnectorConfig,
    get_connector,
    list_connectors,
    register_connector,
)
from nocobase_py.services.connectors.dingtalk import DingTalkConnector
from nocobase_py.services.connectors.feishu import FeishuConnector
from nocobase_py.services.connectors.mattermost import MattermostConnector
from nocobase_py.services.connectors.slack import SlackConnector
from nocobase_py.services.connectors.wecom import WeComConnector

__all__ = [
    "BaseConnector",
    "ConnectorConfig",
    "register_connector",
    "get_connector",
    "list_connectors",
    "SlackConnector",
    "WeComConnector",
    "DingTalkConnector",
    "FeishuConnector",
    "MattermostConnector",
]
