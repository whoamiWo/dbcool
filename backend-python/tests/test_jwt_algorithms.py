"""JWT 多算法支持测试 — 验证 HS384 可被接受 (兼容 Java jjwt 自动选择)."""

import pytest
from jose import jwt as pyjwt
from datetime import datetime, timedelta

from nocobase_py.security import _decode_token
from nocobase_py.config import get_settings


class TestJWTMultiAlgorithm:
    """测试 JWT 多算法支持."""
    
    @pytest.fixture
    def settings(self):
        return get_settings()
    
    def test_hs256_token_accepted(self, settings):
        """HS256 签发的 token 应被接受 (向后兼容)."""
        payload = {
            "user_id": "user-123",
            "username": "testuser",
            "tenant_id": "tenant-1",
            "typ": "access",
            "exp": datetime.utcnow() + timedelta(hours=1),
        }
        token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS256")
        
        result = _decode_token(token)
        
        assert result is not None
        assert result["user_id"] == "user-123"
    
    def test_hs384_token_accepted(self, settings):
        """HS384 签发的 token 应被接受 (兼容 Java jjwt 自动选择)."""
        payload = {
            "user_id": "user-456",
            "username": "testuser2",
            "tenant_id": "tenant-1",
            "typ": "access",
            "exp": datetime.utcnow() + timedelta(hours=1),
        }
        token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS384")
        
        result = _decode_token(token)
        
        assert result is not None
        assert result["user_id"] == "user-456"
    
    def test_hs512_token_accepted(self, settings):
        """HS512 签发的 token 应被接受."""
        payload = {
            "user_id": "user-789",
            "username": "testuser3",
            "tenant_id": "tenant-1",
            "typ": "access",
            "exp": datetime.utcnow() + timedelta(hours=1),
        }
        token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS512")
        
        result = _decode_token(token)
        
        assert result is not None
        assert result["user_id"] == "user-789"
    
    def test_wrong_key_token_rejected(self, settings):
        """错误密钥签发的 token 应被拒绝."""
        payload = {
            "user_id": "user-999",
            "username": "hacker",
            "tenant_id": "tenant-1",
            "typ": "access",
            "exp": datetime.utcnow() + timedelta(hours=1),
        }
        token = pyjwt.encode(payload, "wrong_secret_key", algorithm="HS384")
        
        result = _decode_token(token)
        
        assert result is None
    
    def test_expired_token_rejected(self, settings):
        """过期的 token 应被拒绝."""
        payload = {
            "user_id": "user-expired",
            "username": "olduser",
            "tenant_id": "tenant-1",
            "typ": "access",
            "exp": datetime.utcnow() - timedelta(hours=1),  # 已过期
        }
        token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS384")
        
        result = _decode_token(token)
        
        assert result is None
    
    def test_non_access_token_rejected(self, settings):
        """非 access token 类型的 token 应被拒绝."""
        payload = {
            "user_id": "user-refresh",
            "username": "refreshuser",
            "tenant_id": "tenant-1",
            "typ": "refresh",  # 不是 access
            "exp": datetime.utcnow() + timedelta(hours=1),
        }
        token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS384")
        
        result = _decode_token(token)
        
        assert result is None
