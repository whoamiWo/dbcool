# 三栈质量基线 (Baseline)

## 一、后端 Java 栈

### 1.1 编译与测试
```bash
cd backend-java
mvn clean compile          # ✅ BUILD SUCCESS
mvn test                   # ✅ 936/936 PASS
mvn test -Dtest='Wiki*Test' # ✅ 33/33 PASS
```

### 1.2 代码规范
- **命名约定**: 类名大驼峰，方法名小驼峰，常量全大写 + 下划线
- **注释要求**: 公共 API 必须 Javadoc，复杂逻辑必须行内注释
- **异常处理**: Controller 层统一 `@ExceptionHandler`，Service 层抛业务异常
- **日志规范**: 使用 Slf4j，DEBUG/INFO/WARN/ERROR 四级，禁止 System.out.println

### 1.3 安全红线
- **认证**: 所有端点必须通过 Spring Security 过滤链
- **授权**: 租户隔离 + 角色权限校验 (AclEnforcer)
- **输入验证**: DTO 层 `@NotNull`/@NotBlank/@Size` 注解
- **SQL 注入**: 只允许 MyBatis XML 参数化查询，禁止字符串拼接

### 1.4 性能指标
- **API 响应时间**: P95 < 200ms, P99 < 500ms
- **数据库连接池**: HikariCP maxPoolSize=20, minIdle=5
- **缓存策略**: Redis TTL 5-30 分钟，热点数据预加载
- **异步处理**: 耗时操作走 `@Async` + 独立线程池

---

## 二、前端 React 栈

### 2.1 编译与测试
```bash
cd frontend
npx tsc --noEmit         # ✅ 0 errors
npx vitest run           # ✅ 233/233 PASS
npm run build            # ✅ dist/ 生成成功
```

### 2.2 代码规范
- **组件设计**: 函数组件 + Hooks，禁止 Class 组件
- **类型定义**: TypeScript 严格模式，禁止 `any`
- **样式方案**: MUI v9 `sx` prop + `styled` 函数
- **状态管理**: Zustand store，禁止直接修改 state

### 2.3 测试要求
- **单元测试**: 覆盖率 > 80%，核心组件 100%
- **E2E 测试**: Playwright 覆盖核心流程
- **Mock 策略**: MSW 拦截 API，不依赖真实后端

### 2.4 性能指标
- **首屏加载**: LCP < 2.5s, FID < 100ms
- **Bundle 大小**: 主包 < 500KB, 懒加载 > 100KB 模块
- **渲染性能**: 组件重渲染次数 < 3 次/交互

---

## 三、Python 辅助栈

### 3.1 编译与测试
```bash
cd backend-python
python -m pytest         # ✅ 待补充
black --check .          # ✅ 代码格式化
```

### 3.2 代码规范
- **类型提示**: 所有函数必须 Type Hints
- **文档字符串**: Google Style Docstrings
- **异常处理**: 自定义 `AppException` 继承链
- **日志规范**: `logging` 模块，JSON 格式输出

### 3.3 接口规范
- **协议**: HTTP RESTful + OpenAPI 3.0
- **鉴权**: JWT Bearer Token，过期自动刷新
- **限流**: 基于租户 + IP 双重限流
- **兼容性**: OpenAI 兼容接口，支持多 LLM 切换

---

## 四、集成质量标准

### 4.1 API 契约
- **请求格式**: JSON Content-Type，UTF-8 编码
- **响应格式**: `{ code: number, message: string, data: object }`
- **错误码**: 0=成功，4xx=客户端错误，5xx=服务端错误
- **分页**: `page`, `pageSize`, `total`, `records` 字段

### 4.2 数据库规范
- **Flyway 迁移**: 版本控制，不可变历史
- **索引策略**: 外键 + 查询条件必须索引
- **事务边界**: Service 层 `@Transactional`，禁止跨服务事务
- **软删除**: `deleted` 字段 + 全局查询过滤

### 4.3 部署规范
- **环境变量**: `.env` 文件 + Docker Compose
- **健康检查**: `/actuator/health` + `/api/ping`
- **日志收集**: ELK Stack，结构化日志
- **监控告警**: Prometheus + Grafana，CPU/内存/QPS 指标

---

## 五、验收标准 (Definition of Done)

### 5.1 功能完成
- [ ] 需求文档 100% 覆盖
- [ ] 手动测试通过
- [ ] 自动化测试通过
- [ ] 代码审查通过

### 5.2 质量达标
- [ ] 单元测试覆盖率 > 80%
- [ ] 无严重/高危漏洞 (SonarQube)
- [ ] 性能指标达标 (压测报告)
- [ ] 安全扫描通过 (OWASP ZAP)

### 5.3 文档完整
- [ ] API 文档 (Swagger/OpenAPI)
- [ ] 部署文档 (README.md)
- [ ] 变更日志 (CHANGELOG.md)
- [ ] 用户手册 (USER_GUIDE.md)

---

## 六、持续改进

### 6.1 技术债管理
- **记录**: 所有技术债录入 `RISK_REGISTER.md`
- **优先级**: P0(阻塞) > P1(高) > P2(中) > P3(低)
- **偿还**: 每个 Sprint 预留 20% 资源

### 6.2 质量度量
- **每周**: 测试通过率、代码覆盖率、Bug 趋势
- **每月**: 性能基准、安全扫描、用户反馈
- **每季度**: 架构评审、技术选型复盘

---

## 七、性能容量基线（PHASE70 T2，实测）

> 环境：**单副本**（本机 docker compose，非 K8s）；数据量：空库 + 少量种子数据；
> 工具：k6（`grafana/k6` 离线镜像）；脚本：`perf/load-test.js`。
> 场景：`GET /api/collections`（只读）+ `POST /api/im/messages`（IM 写入，含广播）
> + `POST /api/attachments/upload`（附件上传 → MinIO）。

### 7.1 复现命令

```bash
# 默认（限流开启，即生产配置）
docker run --rm -v $PWD/perf:/scripts --network host \
  -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
  grafana/k6 run /scripts/load-test.js

# 测"系统本身容量"时临时放宽限流（测完必须重启恢复默认 30/60s）
RATELIMIT_IM_LIMIT=100000 RATELIMIT_FILEUPLOAD_LIMIT=100000 \
RATELIMIT_LOGIN_LIMIT=20000 docker compose up -d --no-build backend-java
```

### 7.2 实测数字（阶梯 20 → 50 → 100 VU，共 3m30s）

| 指标 | 限流开启（生产配置） | 限流放宽（系统容量） |
|---|---|---|
| 总请求数 | 24,907 | 24,769 |
| 吞吐 | 137.8 req/s | **137.3 req/s** |
| `http_req_duration` avg | 3.86 ms | 5.75 ms |
| p(50) | 1.81 ms | 6.15 ms |
| **p(95)** | 10.18 ms | **11.45 ms** |
| **p(99)** | 14.2 ms | **14.66 ms** |
| max | 67.76 ms | 78.78 ms |
| `http_req_failed` | **32.96%**（8,211 个 429） | **0.00%** |
| checks 通过率 | — | **100%**（24,765/24,765） |

### 7.3 结论与拐点

- **系统本身（限流放宽）**：100 VU 下 p95 仅 11.45 ms、p99 14.66 ms、**零错误**，
  在本次压测量级下**未出现拐点** —— 单机容量上限高于 137 req/s，需更大压力
  （≥300 VU 或延长阶梯）才能测得拐点。
- **限流开启时**：失败率 32.96% 全部为 **429**，集中在 IM 写入
  （默认 `ratelimit.im.limit=30 / 60s`）。即**生产环境的写入吞吐实际受限于限流配额，
  而非系统容量** —— 这是预期行为（防刷），但意味着：
  - 若业务侧确有高频发消息需求（如机器人/集成入站），需**单独放宽或豁免**该限流键；
  - 压测任何写入接口前必须先确认目标接口的限流阈值，否则测到的是限流而非容量。
- **未覆盖**：Wiki 块保存、工作流触发 —— 当前环境无 wiki page / workflow 数据，
  脚本 `setup()` 抓不到 ID 会自动跳过（日志 `page=N/A workflow=N/A`）。
  补上种子数据后可直接复用同一脚本。

---

**最后更新**: 2026-10-02（PHASE70 追加第七节）  
**维护者**: 三栈开发团队  
**版本**: 1.0.1
