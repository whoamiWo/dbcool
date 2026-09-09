# ADR-006: 认证使用 JWT + Refresh Token

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: 全部

## 背景

- Web + 未来移动端 / CLI 都需认证
- 多租户场景,token 需带 tenant_id
- 减少服务端 session 查询

## 决策

- **Access Token**: JWT,有效期 15 分钟
  - 字段: `sub`(user_id), `tid`(tenant_id), `roles`, `exp`, `iat`
  - 算法: HS256(单服务),后期切 RS256 便于多服务验证
- **Refresh Token**: 随机字符串(32 字节),有效期 7 天,存 Redis
  - 单次使用,刷新后旧 token 失效
- **存储**: 前端 access token 存内存,refresh token 存 HttpOnly Cookie

## Token 生命周期

```
登录
  ↓
生成 access + refresh
  ↓
返回 { access_token, refresh_token }
  ↓
前端 access 存内存,refresh 存 HttpOnly Cookie
  ↓
每次请求带 Authorization: Bearer {access}
  ↓
快过期时(剩 1 分钟)用 refresh 换新 access
  ↓
refresh 也过期 → 重新登录
```

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| Session Cookie | 简单、撤销容易 | 不适合 API、跨域烦 | 我们是前后端分离 |
| OAuth 2.0 | 标准、企业级 | 复杂、本场景 overkill | 暂不需要第三方登录 |
| OIDC | OAuth + 身份层 | 需 Provider | 后期可接 |

## 后果

### 正面
- 无状态,水平扩展简单
- 多端友好(Web / 移动 / CLI)
- tenant_id 在 token 中,减少查询

### 负面
- Access token 撤销复杂(只能短过期)
- 密钥泄露需全员重新登录

### 缓解措施
- 短期 access + refresh 配合
- Refresh token 用 Redis 黑名单支持主动撤销
- 密钥用 Vault / KMS 管理,定期轮换
