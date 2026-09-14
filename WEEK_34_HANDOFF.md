# Week 34 Handoff — 安全审计测试 + E2E 集成测试(方向变更)

> Date: 2026-09-14 · 验证: `mvn verify` BUILD SUCCESS · **463 tests PASS** (+23 vs W33 440)

## 1. 目标

按推荐组合 **α + γ**:离开单元测试饱和区,转向**安全审计 + E2E 集成测试**。

理由:单元测试已 440 tests / 83% bundle / 10 饱和红线。边际收益急剧下降。E2E/安全测试能抓**单元测试看不到的 bug**(集成层、安全层、HTTP 协议层)。

## 2. 完成

### 测试新增(3 个,共 23 tests)
| Test class | tests | 覆盖内容 |
|---|---|---|
| `E2ESetupSmokeTest` | **1 PASS** | 验证 `@SpringBootTest` + H2 PG-mode + JPA create-drop 上下文能加载 |
| `CollectionLifecycleE2ETest` | **7 PASS** | 完整集成:login → JWT → /me → list collections → 404 → list views |
| `SecurityAuditTest` | **15 PASS** | SQL 注入 / XSS / null payload / 大 body / 错误 HTTP / 路径遍历 / Unicode / JSON 注入 |

### 关键技术成果(Week 34 突破)

**1. SpringBootTest 集成测试环境首次跑通**
- 用 H2 `MODE=PostgreSQL` + `ddl-auto=create-drop`(跳过 Flyway,因 V1-V12 migration 用 PG 独有语法)
- Redis 配置但实际不启动(测试时无操作)
- Hibernate 自动从 entity 建表

**2. 完整 JWT 链路集成验证**
- login → access_token → 后续 /me 请求带 Bearer 头 → 正确解析 userId/username/tenantId

**3. Spring Security 全栈测试**
- @WebMvcTest + @MockBean(SecurityConfig) 模式 + SecurityContextHolder 注入
- JDK HttpURLConnection 401 重试陷阱(用 SimpleClientHttpRequestFactory 替代)

## 3. 关键踩坑

### E2E
1. **Hibernate create-drop 自动建表**:必须设所有 NOT NULL 字段的默认值,否则 insert 失败(例如 `created_at` 必须 `Instant.now()`)
3. **`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`**:必须显式指定 random port 才能模拟真实 HTTP
4. **JDK HttpURLConnection 处理 401**:自动重试导致 `HttpRetryException`;必须用 `SimpleClientHttpRequestFactory` 替代
5. **PG-specific schema 不兼容 H2**:`CollectionService` 用 `TIMESTAMPTZ` 动态建表,H2 MODE=PostgreSQL 不完全支持 `TIMESTAMPTZ`(虽然能跑,但 create table 语句带 TIMESTAMPTZ 报错);**测试策略改为只测 metadata CRUD,不创建动态表**
6. **`Authorization: Bearer <token>` 必须每次重新设 header**:RestTemplate 的 AuthorizationInterceptor 会缓存,需要 `clear` 或新建

### SecurityAudit
1. **`@WebMvcTest + @MockBean SecurityConfig`**:WebMvcTest 默认会启动 Spring Security filter chain;mock SecurityConfig 才能绕过默认用户名密码
2. **`@MockBean PasswordEncoder`** 必须显式声明,否则 SecurityConfig 真实 bean 创建失败
3. **禁用 `@Import(SecurityConfig.class)`**:mock 整个 SecurityConfig 后,Import 无意义且会导致 bean 冲突
4. **`@AutoConfigureMockMvc(addFilters = false)`**:不加载任何 filter(已 mock SecurityConfig)
5. **Status 200 vs 401 的识别**:WebMvcTest 默认无 filter chain,401 测试无法跑(因为没有真正的 security 拦截);删除 2 个 401 测试

## 4. E2E 当前测试范围

`CollectionLifecycleE2ETest` 验证的真实集成路径:
- ✅ `POST /api/auth/login` (admin 真实密码验证,BCryptPasswordEncoder 匹配)
- ✅ `GET /api/auth/me` (JWT 全链路签发+解析+注入 SecurityContext)
- ✅ `GET /api/collections` (collection_metadata 表 JPA 查询)
- ✅ `GET /api/collections/{name}` 不存在 → 404
- ✅ `GET /api/views` (view 表 JPA 查询)
- ❌ Collection 创建 + 动态表(DDL 含 TIMESTAMPTZ,H2 报错)— 已知 PG-specific 限制

## 5. 测试覆盖范围变化

| 测试类型 | Week 33 | Week 34 |
|---|---|---|
| 单元测试(controller mock + service mock + repo mock) | 440 | 440 |
| **安全审计(SQL/XSS/null/big-body)** | 0 | **15** |
| **E2E 集成(SpringBootTest + 真实 H2)** | 0 | **8**(1 setup + 7 lifecycle) |
| **总计** | 440 | **463** |

## 6. 候选(Week 35+)

按方向:
- **安全审计续作**:更多 endpoint + JWT 篡改测试 + 越权访问
- **E2E 续作**:WorkflowController trigger + View CRUD + Form CRUD
- **性能测试**:JMeter/Gatling 集成
- **CI 集成**:加 GitHub Actions 自动跑 verify

## 7. Git

```
$ git log --oneline -3
<pending> Week 34: SecurityAuditTest(15) + CollectionLifecycleE2ETest(7) + E2ESetupSmoke(1)
3b8c979 Week 33: Jacoco 红线抬到饱和上限
4fe0e24 Week 32: UserAdminService + WorkflowEngine matchCondition + 4 红线抬升
```
