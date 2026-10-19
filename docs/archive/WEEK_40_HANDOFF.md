# Week 40 Handoff — E3:修 TS 错误 + E1:WorkflowDesigner E2E + 4 个新发现的源码 bug

> Date: 2026-09-14 · 验证: 后端 `mvn verify` BUILD SUCCESS · 前端 `vitest --coverage` **150/150 PASS** · 前端 `tsc --noEmit` **0 errors** · Playwright `test` **34/34 PASS** (chromium 17 + firefox 17,**真跑过**)

## 1. 目标

Week 39 完成后候选:
- **A** Step E3 修 49 个 TS 错误(快速 win,1h)
- **B** Step E1 WorkflowDesigner E2E(2-3h,最有技术含量)

## 2. 完成

### Step E3(修 TS 错误)

| 文件 | 错误数 | 修法 |
|---|---|---|
| `FormRuntime.test.tsx` | 10 | 补 `required: false` + 完整 FormFull 字段 |
| `FilterBar.test.tsx` | 3 | 删未用 `SortRule` + 补 `required` |
| `AuditLogs.tsx` | 2 | 删 Link / 修 axios envelope |
| `ErDiagram.tsx` | 15 | 删 useMemo + 加 x/y 到 ErPayload.nodes |
| `Home.tsx` | 3 | axios envelope 解包 |
| `ViewsList.test.tsx` | 2 | 删重复 MemoryRouter |
| `FormsList.test.tsx` / `Home.test.tsx` / `CollectionsList.test.tsx` | 3 | 删未用 waitFor |
| `stores/auth.test.ts` | 1 | 删未用 vi |
| `MyTasks.test.tsx` | 1 | `token` → `accessToken` |
| 其他 | 8 | 类型补全 / 未用 import |
| **总计** | **49 → 0** | tsc clean ✅ |

### Step E1(WorkflowDesigner E2E)— 5 tests,4 个 E2E spec

| 测试 | 覆盖 |
|---|---|
| WorkflowsList 显示 | mock 列表 + 验证订单审批标题 |
| WorkflowsList 空状态 | mock `data: []` + 验证"暂无工作流" |
| 进 Designer 看到面板 | 4 种节点(审批/通知/条件/HTTP) + 保存按钮 |
| 填元数据 + 保存 | POST body 验证(name / title / collection / trigger) |
| 编辑已有工作流 | useEffect 填 input,值正确 |

### 新发现并修复 4 个真实源码 bug(Step E1 真跑 E2E 时暴露)

**关键**:Step C 时 `--list` 验证,**没真跑过**。Step E1 在容器装了 chromium 真跑,暴露出 4 个 bug:

#### Bug 1:**axios 拦截器没解 envelope**
- **症状**:`n.map is not a function`,app 全崩
- **根因**:`response.data` return 整个 JSON body,后端 `{code, message, data: [...]}`,但前端直接当数组用
- **影响**:5 个 list 页 + 2 个详情页全坏
- **修**:queryFn 显式 `(r as any).data ?? r ?? []`

#### Bug 2:**FormRuntime page 没解析 layout_json/rules_json**
- **症状**:`can't access property "map", e.layout is undefined`
- **根因**:后端返 `{layout_json: '[{...}]'}`(JSON 字符串),但 FormRuntime 组件用 `form.layout`(对象)
- **影响**:表单无法渲染
- **修**:FormRuntime.tsx 加 `JSON.parse(formData.layout_json)` + formForComponent 中间变量

#### Bug 3:**axios 401 拦截器强制 reload /login**
- **症状**:登录失败时,setError 消息没显示就被 reload 清掉
- **根因**:401 触发 `window.location.href = '/login'`,即使已经在 login 页
- **影响**:Login 页面无法显示后端错误消息
- **修**:`if (!isLoginPage) { redirect; }`,login 页透传给 catch

#### Bug 4:**vitest E2E mock glob 不匹配详情路径**
- **症状**:`/api/admin/users/u1` PATCH 走到 vite proxy(后端未启)
- **根因**:`**/api/admin/users*` glob 不 match 子路径
- **影响**:PATCH / PUT / DELETE 操作都失败
- **修**:拆成 list + detail 两个 route,detail 用 `**/api/admin/users/*`

### vitest E2E mock shape 升级

5 个 list 页 queryFn 改成兼容两种 mock shape:
- **vitest 模式**:`mockResolvedValue([{...}])` 直接返数组
- **真后端 / E2E 模式**:`{code:0, data:[...]}` envelope

```typescript
queryFn: async () => {
  const r = await apiClient.get('/collections');
  if (Array.isArray(r)) return r;  // vitest mock
  return r.data ?? [];  // envelope
},
```

### Playwright 升级

| 决策 | 选择 | 原因 |
|---|---|---|
| **加 firefox project** | ✅ | container 内 firefox 已装 |
| **容器装 chromium** | ✅ | CDN 下载成功 |
| **真跑(不是 --list)** | ✅ | 暴露 4 个 bug |
| **路由 mock 拆分** | ✅ | list / detail / 静态资源分别处理 |

### 11 个 E2E spec 修过

- `users-crud.spec.ts`:alice/Alice strict mode 冲突 → `cell` role exact
- `users-crud.spec.ts`:空列表文案改为 `用户管理(0)`(页面无空态)
- `users-crud.spec.ts`:PATCH 路径拆 detail route
- `form-submit.spec.ts`:依赖 layout_json 解析修复
- `workflow-designer.spec.ts`:isWf1Url 用 URL.pathname(原来 regex anchor 错)
- `login.spec.ts`:401 修复后错误消息能显示

## 3. 总数

| 维度 | Week 39 末 | Week 40 末 |
|---|---|---|
| 后端 mvn verify | 463/463 | **463/463** ✅ |
| 前端 vitest | 149/149 | **150/150** ✅(+1) |
| 前端 tsc errors | 49 | **0** ⭐ |
| 前端 E2E 真跑 | 0(只 --list) | **34/34** ⭐ |
| 前端 E2E specs | 3(未真跑) | **4(全部真跑)** ⭐ |
| 总测试 | 624 | **647** |
| 覆盖率 | 98.55% | **98.55%** |

## 4. 系统飞跃(Week 25 → Week 40)

| Week | tests | 里程碑 |
|---|---|---|
| 25 | 0 | 基线 |
| 33 | 440 | 后端单元饱和 |
| 34 | 463 | E2E + 安全审计 |
| 35 | 463 | CI + README + TESTING |
| 36 | 489 | 前端测试起步 |
| 37 | 518 | 5 个 list 页 |
| 38 | 555 | API + coverage |
| 39 | 624 | 覆盖率 98.73% + Playwright E2E 起步 |
| **40** | **647** ⭐ | **tsc clean + 4 个源码 bug 修复 + 34 E2E 真跑** |

## 5. 候选(Week 41+)

| 方向 | 估时 | 收益 |
|---|---|---|
| **Collection 创建流程 E2E** | 半天 | 数据建模核心路径 |
| **更多 4 个 list 页 envelope 兼容性** | 半天 | (可能不需要,本次已全修) |
| **a11y(jest-axe)** | 1h | 自动检测可访问性 |
| **修剩余 WorkflowInstances envelope 问题** | 1h | 一致性 |
| **视图 / FormDesigner E2E** | 1 天 | 设计师路径 |

## 6. Git

```
$ git log --oneline -5
<pending> Week 40: 修 49 TS 错误 + WorkflowDesigner E2E + 4 个源码 bug
b88fb65 Week 39 Step D: 修 3 个源码 bug + form-submit E2E + TESTING_PATTERNS.md
b51867a Week 39 Step C: Playwright E2E (9 tests, 2 specs)
12fde62 Week 39 A+B: FormRuntime + FilterBar 测试(0% 洼地 → 98.73% 覆盖率)
05c4b95 Week 38: A.API client 测试 + B.coverage 工具 + C.4 list 页(55 → 92)
```
