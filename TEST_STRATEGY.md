# 测试策略

> 创建日期: 2026-09-09
> 适用项目: NocoBase 级低代码平台
> 测试金字塔: 70% 单元 + 20% 集成 + 10% E2E

---

## 一、测试分层

```
                  ╱╲
                 ╱  ╲           E2E (10%)
                ╱ Playwright╲     慢、脆、但必须
               ╱──────────────╲
              ╱                ╲
             ╱   契约测试(5%)   ╲  Pact + OpenAPI
            ╱────────────────────╲
           ╱                      ╲
          ╱    集成测试(15%)        ╲  Spring Boot Test + Testcontainers
         ╱──────────────────────────╲
        ╱                            ╲
       ╱      单元测试(70%)            ╲  JUnit 5 + pytest + Vitest
      ╱────────────────────────────────╲
```

## 二、各栈测试策略

### ☕ Java(Spring Boot)

| 层级 | 工具 | 覆盖目标 | 比例 |
|---|---|---|---|
| 单元 | JUnit 5 + Mockito + AssertJ | Service / Util / Domain | 70% |
| 集成 | Spring Boot Test + Testcontainers | Controller / Repository / Engine | 20% |
| 契约 | Spring Cloud Contract | 暴露给前端的 API | 5% |
| 性能 | JMH | 关键算法 | 5% |

**关键测试场景:**
- Collection 引擎:并发创建/修改表不冲突
- ACL 引擎:权限矩阵正确性
- 工作流引擎:死循环检测、超时处理
- 多租户:跨租户访问被拒

**覆盖率目标:** ≥ 70%(行)/ 60%(分支)

### 🐍 Python(FastAPI)

| 层级 | 工具 | 覆盖目标 | 比例 |
|---|---|---|---|
| 单元 | pytest + factory_boy | Service / Pydantic model | 70% |
| 集成 | pytest + httpx + respx | FastAPI endpoint | 15% |
| 契约 | schemathesis(自动 fuzz) | 暴露给前端的 API | 5% |
| E2E | Locust | 性能/负载 | 10% |

**关键测试场景:**
- LLM 调用 mock 测试(避免真实扣费)
- Celery 任务幂等性
- 异步任务超时与重试
- 与 Java 的 HTTP 调用模拟

**覆盖率目标:** ≥ 80%(行)/ 70%(分支)

### 🌐 React(TypeScript)

| 层级 | 工具 | 覆盖目标 | 比例 |
|---|---|---|---|
| 单元 | Vitest + Testing Library | Component / Hook / Util | 60% |
| 集成 | Vitest + MSW | 完整页面交互 | 25% |
| E2E | Playwright | 用户旅程 | 15% |

**关键测试场景:**
- 设计器拖拽精度
- Schema 变更后 UI 正确响应
- 权限拒绝时 UI 隐藏对应控件
- 表单校验交互

**覆盖率目标:** ≥ 75%(行)/ 65%(分支)

## 三、契约测试(跨栈)

### 工具
- **Pact**(双向契约测试)
- **OpenAPI Schema 验证**(用 schemathesis 自动生成 fuzz case)

### 流程
```
Java 提供方 → 生成 Pact 文件 → 推到 Pact Broker
  ↓
前端消费方 → 拉取 Pact → 验证消费侧兼容
  ↓
前端提供方 → 生成 Pact → 推到 Pact Broker
  ↓
Java 消费方 → 拉取 Pact → 验证兼容
```

### 触发
- 每次 CI 自动跑
- 任何 API 变更时强制

## 四、关键 E2E 场景(演示前必跑)

| 编号 | 场景 | 频率 |
|---|---|---|
| E2E-01 | 登录 → 创建表 → 加字段 → 保存 | 每次 commit |
| E2E-02 | 创建表单 → 拖拽字段 → 预览 → 提交数据 | 每次 commit |
| E2E-03 | 创建表格视图 → 筛选 → 排序 → 分页 | 每次 commit |
| E2E-04 | 创建角色 → 分配字段权限 → 验证隐藏 | 每次 commit |
| E2E-05 | 创建工作流 → 触发 → 节点执行 | 每次 commit |
| E2E-06 | 跨租户访问被拒 | 每次 commit |
| E2E-07 | 完整演示流程(33 个 P0 故事串联) | 每周 + 演示前 |

## 五、性能测试

| 场景 | 目标 | 工具 |
|---|---|---|
| 200 并发用户登录 | p95 < 500ms | k6 |
| 100 万条数据查询 | < 1s | k6 + EXPLAIN |
| 工作流并发触发 100 次 | 无丢失 | k6 |
| 拖拽设计器操作 | < 100ms 响应 | Chrome DevTools |
| 大表单(50 字段)加载 | < 2s | Lighthouse |

## 六、测试数据管理

### 单元测试
- 每个测试自己造数据(factories)
- 不依赖外部状态

### 集成测试
- Testcontainers 起 Postgres + Redis
- `@Sql` 注解加载 fixture

### E2E 测试
- Playwright 全局 `beforeAll`:重置 DB + 加载 demo 数据
- 每个测试独立 `beforeEach`:重置

## 七、CI/CD 流水线

```
commit → CI 触发
  ↓
├─ Lint(三栈并行)            2 分钟
├─ 单元测试(三栈并行)        5 分钟
├─ 集成测试(三栈并行)        8 分钟
├─ 契约测试                  3 分钟
├─ 构建产物(三栈并行)        5 分钟
└─ E2E(关键场景)             10 分钟
                              ─────────
                              总计 ≤ 20 分钟
```

## 八、覆盖率门槛

| 栈 | 单元覆盖率门槛 | 集成覆盖率门槛 |
|---|---|---|
| Java | ≥ 70% | ≥ 60% |
| Python | ≥ 80% | ≥ 70% |
| React | ≥ 75% | ≥ 65% |
| 契约 | 100% 路径覆盖 | — |

低于门槛时 CI 失败。

## 九、阶段 3~4 期间的临时策略

MVP 期间(Week 3~16)优先级:

1. **P0 故事必须有 E2E**(US-XXX 验收时附 e2e 用例)
2. **关键引擎必须有单元测试**(Collection / ACL / Workflow)
3. **契约测试只在 Java ↔ Frontend 之间**(Python 集成 e2e 代替)
4. **性能测试放到阶段 6** 统一做

## 十、阶段 6(优化期)策略

引入 Prime 后:
- Prime 改代码必须有测试
- Cline 审查时检查测试是否充分
- 覆盖率不下降作为合并门槛
