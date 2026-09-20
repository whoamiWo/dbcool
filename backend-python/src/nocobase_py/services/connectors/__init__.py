"""外部连接器包。"""

from nocobase_py.services.connectors.dingtalk import DingTalkConnector
from nocobase_py.services.connectors.feishu import FeishuConnector
from nocobase_py.services.connectors.slack import SlackConnector
from nocobase_py.services.connectors.wecom import WeComConnector

__all__ = ["SlackConnector", "WeComConnector", "DingTalkConnector", "FeishuConnector"]