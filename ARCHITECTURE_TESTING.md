# 测试架构(Week 35)

> 创建日期: 2026-09-14
> 适用项目: NocoBase Java 后端
> 维护者: Cline

## 一、测试金字塔

```
        /\
       /E \        E2E 集成(SpringBootTest + H2)
      / 8  \       8 tests
     /----\
    /SA \          安全审计(SQL 注入 / XSS / 越权)
   / 15  \        15 tests
  /------\
 / UNIT   \       单元测试(Mockito + @WebMvcTest)
/ 440     \      440 tests
------------
```

| 层 | 数量 | 抓的 bug 类型 | 速度 | 脆弱性 |
|---|---|---|---|---|
| **单元** | 440 | 业务逻辑错误、边界条件 | 极快(<10s) | 低 |
| **安全审计** | 15 | 输入验证漏洞、SQL 注入、XSS | 快(<30s) | 低 |
| **E2E** | 8 | 集成层 bug、JPA 映射、HTTP 协议 | 慢(<60s) | 中 |

## 二、单元测试模式

### 2.1 Controller 测试模板

```java
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)  // mock SecurityConfig
class AuthControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean SecurityConfig securityConfig;  // 绕过 Spring Security 默认用户
    @MockBean UserRepository userRepository;
    @MockBean UserRoleRepository userRoleRepository;
    @MockBean PasswordEncoder passwordEncoder;
    @MockBean JwtService jwtService;
    @MockBean RefreshTokenService refreshTokenService;
    @MockBean JwtAuthFilter jwtAuthFilter;
    
    @Test
    void testXxx() throws Exception {
        // given: mock 依赖
        when(userRepository.findByUsername("alice")).thenReturn(...);
        
        // when + then
        mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{...}"))
            .andExpect(status().isOk());
    }
}
```

**关键点**:
1. **`@MockBean(SecurityConfig)`**:WebMvcTest 默认启用 Spring Security filter,会生成默认用户名密码;mock 整个 SecurityConfig 才能纯净测 controller
2. **`@AutoConfigureMockMvc(addFilters = false)`**:完全不加载 filter chain
3. **必须 mock 所有 controller 依赖**(包括间接依赖如 UserRoleRepository)
4. **`SecurityContextHolder.setContext(...)`**:手动注入 Authentication,模拟登录用户
5. **`@AfterEach { SecurityContextHolder.clearContext() }`**:避免测试间污染

### 2.2 Service 测试模板

```java
class AuditServiceTest {
    private AuditLogRepository repo;
    private AuditService service;
    
    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        service = new AuditService(repo, new ObjectMapper());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
    
    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }
    
    @Test
    void log_capturesIpFromXff() {
        bindRequest("203.0.113.5", "Mozilla");
        service.log("t", UUID.randomUUID(), "u", "X", "y", "z", null);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(...);
        verify(repo).save(cap.capture());
        assertEquals("203.0.113.5", cap.getValue().getIp());
    }
}
```

**关键点**:
1. **直接 `new Service(deps)`**,不依赖 Spring context
2. **每个测试自己 setup**,避免 `@BeforeAll` 共享状态
3. **`ArgumentCaptor` 验证复杂对象**(不能 `eq()` 比对)
4. **`RequestContextHolder` 必须 reset**(用 ServletRequestAttributes 时)

### 2.3 Engine / 复杂组件 测试模式

对于内部依赖很多、难直接 `new` 的组件:

```java
class WorkflowEngineTest {
    @BeforeEach
    void setUp() throws Exception {
        engine = new WorkflowEngine(...);
        // RestTemplate 是 final 字段,用反射替换
        Field f = WorkflowEngine.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(engine, mockRestTemplate);
    }
}
```

## 三、E2E 集成测试模式

### 3.1 基础设施

E2E 测试用 **H2 `MODE=PostgreSQL`** + **JPA `create-drop`**,绕过 Flyway(V1-V12 用 PG 独有语法)。

**Profile 配置** (`src/test/resources/application-test.properties`):
```properties
spring.datasource.url=jdbc:h2:mem:nocobase_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.flyway.enabled=false
```

### 3.2 测试模板

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CollectionLifecycleE2ETest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    
    @BeforeEach
    void setUpAdminUser() {
        // 必须显式设 NOT NULL 字段(自动建表模式)
        if (userRepository.findByUsername("e2e_admin").isEmpty()) {
            UserEntity u = new UserEntity();
            u.setId(UUID.randomUUID());
            u.setUsername("e2e_admin");
            u.setPasswordHash(passwordEncoder.encode("admin123"));
            u.setTenantId("tenant_default");
            u.setCreatedAt(Instant.now());  // ← 必须!
            userRepository.save(u);
        }
    }
    
    @Test
    void login_returns200AndJwt() {
        ResponseEntity<Map> resp = rest.exchange(
                "http://localhost:" + port + "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "e2e_admin", "password", "admin123"),
                        jsonHeaders()),
                Map.class);
        assertEquals(200, resp.getStatusCode().value());
        // 验证 JWT 三段
        String token = (String) ((Map) resp.getBody().get("data")).get("access_token");
        assertTrue(token.split("\\.").length == 3);
    }
}
```

### 3.3 关键踩坑

1. **JDK HttpURLConnection 处理 401**:`WWW-Authenticate` 头触发自动重试,导致 `HttpRetryException`。用 `SimpleClientHttpRequestFactory` 替代
2. **`PG-specific schema 不兼容 H2`**:`TIMESTAMPTZ` / `JSONB` / `uuid_generate_v4()` 报错;**测试策略改为只测 metadata CRUD**,不创建动态表
3. **Hibernate create-drop 自动建表**:必须显式设 NOT NULL 字段(如 `created_at = Instant.now()`)
4. **每个 `@SpringBootTest` 启动慢**(10-15s),e2e suite 用 `mvn verify` 一并跑

## 四、安全审计测试模式

### 4.1 SQL 注入 / XSS / null 攻击向量

```java
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)  // 不需要真鉴权
class SecurityAuditTest {
    @Test
    void login_sqlInjectionInUsername_returns401() throws Exception {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        String body = "{\"username\":\"admin' OR '1'='1\",\"password\":\"anything' OR 1=1--\"}";
        mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());  // 必须 401,而非 500
    }
    
    @Test
    void login_xssInUsername_doesNotExecute() throws Exception {
        String body = "{\"username\":\"<script>alert('xss')</script>\",\"password\":\"p\"}";
        // 期望:被当作普通字符串处理,不会执行
    }
    
    @Test
    void login_extremelyLongUsername_handledGracefully() throws Exception {
        String body = "{\"username\":\"" + "a".repeat(10000) + "\",\"password\":\"p\"}";
        // 期望:不崩溃
    }
}
```

### 4.2 测的攻击向量清单

| 类型 | Payload 示例 | 期望 |
|---|---|---|
| SQL 注入 | `admin' OR '1'='1` | 401 |
| SQL 注入 | `' OR 1=1--` | 401 |
| XSS | `<script>alert('xss')</script>` | 不执行 |
| null 字段 | `{"username":null}` | 400 |
| 大 body | 10KB username | 不崩溃 |
| Unicode | `用户🔐\u0000` | 处理优雅 |
| JSON 注入 | `{"role":"admin"}` 多余字段 | 不提升权限 |
| 错误 HTTP 方法 | GET /api/auth/login | 405 |
| 路径遍历 | `../../../etc/passwd` | 4xx |

## 五、覆盖率门禁(Jacoco)

### 5.1 当前红线(Week 33 饱和)

| 包 | 红线 | 实绩 | 富余 |
|---|---|---|---|
| BUNDLE | 0.82 | 83% | +1% |
| auth | 0.90 | 92% | +2% |
| meta | 0.50 | 58% | +8% |
| workflow | 0.95 | 96% | +1% |
| audit | 0.97 | 99% | +2% |
| view | 0.97 | 98% | +1% |
| notification | 0.90 | 94% | +4% |
| form | 0.95 | 99% | +4% |
| api | 0.95 | 100% | +5% |
| acl | 0.85 | 89% | +4% |

### 5.2 抬升红线工作流

```bash
# 1. 跑 mvn verify 看当前覆盖率
cd backend-java && mvn verify

# 2. 查看 Jacoco 报告
open target/site/jacoco/index.html

# 3. 编辑 pom.xml 红线(必须 < 当前实绩 - 2% 富余)
# <minimum>0.85</minimum>  →  <minimum>0.87</minimum>

# 4. 再次 verify,失败则:
#    a) 找失血源(target/site/jacoco/<pkg>/index.html)
#    b) 写测试覆盖失血分支
#    c) 重跑 verify
```

### 5.3 excludes 规范

只对以下情况 excludes:
- **配置类**(`SecurityConfig`/`OpenApiConfig`/`AsyncConfig`):无业务逻辑
- **中间件**(`JwtAuthFilter`):Mock servlet 成本 > 收益
- **决定跳过的大件**(`CollectionService`/`AsyncMigrationService`):Week 25 决策

移除 exclude 规则:**当且仅当**该类覆盖率 ≥ 80%。

## 六、CI 集成

### 6.1 GitHub Actions workflow

`.github/workflows/backend-ci.yml`:
- **触发**:push 到 main / PR
- **步骤**:Checkout → JDK 21 → `mvn verify` → Upload Jacoco → Coverage Summary
- **超时**:15 分钟
- **并发取消**:同 ref 旧 run 自动取消

### 6.2 失败调试

如果 CI 失败:
1. 看 "Run mvn verify" 步骤的日志 → 找 `Rule violated` 行
2. 下载 `jacoco-coverage-report` artifact → 看 `<pkg>/index.html` 找失血类
3. 修代码或写测试 → push → 自动重跑

## 七、未来方向

### 7.1 E2E 覆盖率扩展

当前 E2E 只覆盖 AuthController。可扩展:
- WorkflowController trigger / myTasks
- ViewController CRUD
- FormController CRUD
- RoleACL 权限验证

每个 controller 加 3-5 E2E 测试,预期 +20-30 tests。

### 7.2 性能测试

用 JMeter / Gatling 测:
- 10k records 查询 < 200ms
- 100 并发 workflow trigger 正确性
- JWT 验签 1ms 延迟

### 7.3 分支覆盖率提升

当前行覆盖率 83%,**分支覆盖率**约 60%。可在 pom.xml 加:
```xml
<limit>
    <counter>BRANCH</counter>
    <value>COVEREDRATIO</value>
    <minimum>0.80</minimum>
</limit>
```

抓 missing else 分支 / try-catch 边缘 / boundary conditions。

## 八、参考资料

- [Mockito Javadoc](https://javadoc.io/doc/org.mockito/mockito-core)
- [Spring Boot Testing Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [H2 Database PG Mode](https://h2database.com/html/grammar.html#compatibility)
- [Jacoco Coverage Goals](https://www.jacoco.org/userdoc/coveragereport.html)
- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
