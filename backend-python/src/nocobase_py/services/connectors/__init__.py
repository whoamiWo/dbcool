"""外部连接器包。"""

from nocobase_py.services.connectors.base import (
    BaseConnector,
    ConnectorConfig,
    register_connector,
    get_connector,
    list_connectors,
)
from nocobase_py.services.connectors.dingtalk import DingTalkConnector
from nocobase_py.services.connectors.feishu import FeishuConnector
from nocobase_py.services.connectors.slack import SlackConnector
from nocobase_py.services.connectors.wecom import WeComConnector
from nocobase_py.services.connectors.mattermost import MattermostConnector

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