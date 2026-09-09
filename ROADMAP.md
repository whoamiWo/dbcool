# 阶段路线图

> 创建日期: 2026-09-09
> 关联: IDEA_BRIEF.md, MVP_SCOPE.md, USER_STORIES.md, ARCHITECTURE.md

---

## 一、总览(7 个阶段,约 30 周)

```
阶段 0  💡 想法澄清          Week 1         [✅ 已完成]
阶段 1  🏛️ 架构决策          Week 1~2       [✅ 已完成 — 当前阶段]
阶段 2  📦 MVP 定义          Week 2~3       [✅ 已完成 — MVP_SCOPE + USER_STORIES]
阶段 3  🏗️ 脚手架搭建        Week 3~6       [⏳ 下一步]
阶段 4  🧪 MVP 实现          Week 7~16      [ ]
阶段 5  📏 基线建立          Week 17        [ ]
阶段 6  🔄 迭代优化          Week 17~30     [ ]
阶段 7  ✨ 终止判定          Week 30+       [ ]
```

---

## 二、阶段 3:脚手架(Week 3~6)

**目标**: 三栈各自可独立启动 + 跨栈最小可跑通链路

### 3.1 Week 3 — 基础设施与脚手架

#### 后端 Java(Spring Boot)
- [ ] 创建 `pom.xml`,定版本(Spring Boot 3.2, JDK 21)
- [ ] 模块划分:`meta` / `acl` / `workflow` / `api` / `auth`
- [ ] Docker Compose 本地 Postgres + Redis + MinIO
- [ ] Flyway 初始化 migration
- [ ] `GET /api/health` 通

#### 后端 Python(FastAPI)
- [ ] 创建 `pyproject.toml`(uv 管理)
- [ ] 模块划分:`ai` / `integration` / `tasks` / `reports`
- [ ] FastAPI 启动
- [ ] `GET /api/health` 通

#### 前端 React(Vite)
- [ ] 创建 Vite + React + TS 项目
- [ ] ESLint + Prettier + tsc 配置
- [ ] Tailwind / CSS Modules 决定
- [ ] React Router 骨架
- [ ] 登录页(静态)

### 3.2 Week 4 — 跨栈最小链路

- [ ] Java 暴露 `POST /api/auth/login` → 返回 JWT
- [ ] Python 暴露 `GET /api/ai/echo`
- [ ] 前端:
  - 登录页提交 → 拿到 JWT
  - 用 JWT 调 Java `GET /api/users/me`
  - 用 JWT 调 Python `GET /api/ai/echo`
- [ ] Nginx 反代,前端 → Java/Python 路由
- [ ] 契约: `contracts/openapi.yaml` 至少有 `/auth/login`、`/users/me`、`/ai/echo`

### 3.3 Week 5 — 数据模型骨架

- [ ] Java `meta` 模块:`collection_meta` 表 + 基础 CRUD API
- [ ] 前端 `schema-designer` 最小可用:创建表 + 添加字段 + 列表
- [ ] 字段类型支持:`text`、`number`、`date`(其他后续)
- [ ] 跨栈契约:`POST /api/collections` + `GET /api/collections`

### 3.4 Week 6 — 第一个端到端能力

- [ ] 前端 Schema Designer → Java 建表 → 前端看 metadata → 创建一条记录 → 前端看记录
- [ ] 一个完整闭环,能演示给一个人看
- [ ] CI 流水线(三栈独立测试 + 契约测试)

---

## 三、阶段 4:MVP 实现(Week 7~16)

按 6 个 Epic 串行实现:

| 周次 | Epic | 验收 |
|---|---|---|
| 7~8 | Epic 1 数据模型(剩余字段类型 + 关联) | 10 种字段可用 + 一对多 |
| 9 | Epic 2 表单(US-101~106) | 6 个 P0 故事全过 |
| 10 | Epic 3 视图(US-201~206) | 6 个 P0 故事全过 |
| 11~12 | Epic 4 权限(US-301~305) | 6 个 P0 故事全过 |
| 13~15 | Epic 5 工作流(US-401~407) | 7 个 P0 故事全过 |
| 16 | Epic 6 平台基础(US-501~504) | 4 个 P0 故事全过 |

**每周固定仪式:**
- 周一:规划会议(本周故事拆分)
- 周三:中期检查
- 周五:Demo + 故事勾选

---

## 四、阶段 5:基线建立(Week 17)

引入之前准备的模板(目前暂缓):
- `BASELINE.md` 三栈基线
- `*_QUALITY_RADAR.md` 三栈质量雷达
- `CHANGELOG_AI.md` AI 变更日志

**进入阶段 6 的前提**: 33 个 P0 故事全部完成 + MVP 演示通过

---

## 五、阶段 6:迭代优化(Week 17~30)

启动 Prime 三栈 daemon(`./opt-multistack.sh start`):

```
prime-java     → 处理 Java 后端的优化任务
prime-python   → 处理 Python 的优化任务
prime-js       → 处理前端的优化任务
prime-contract → 跨栈契约监督(只读)
```

Cline 角色:
- Plan 模式:复盘、决策、任务编排
- Act 模式:跨栈任务、审查 Prime 改动、写 FEEDBACK.md

每周固定:
- 周一:看上周 Prime 报告 + 安排本周任务
- 周三:中审
- 周五:Demo(可选)

---

## 六、阶段 7:终止判定(Week 30+)

执行 Cline 提示词 7.0:
- ✅ 33 个 P0 + 13 个 P1 全完成
- ✅ BASELINE 中指标全达标
- ✅ 三栈 lint 0 错误
- ✅ 200 并发压力测试通过
- ✅ 所有 ADR 已实施

生成 `TERMINATION.md` + v1.0 release notes。

---

## 七、关键里程碑(Milestone)

| 编号 | 名称 | 阶段 | 完成标准 |
|---|---|---|---|
| M0 | 想法澄清 | 0 | IDEA_BRIEF.md |
| M1 | 架构锁定 | 1 | ADR-001~008 + ARCHITECTURE.md |
| M2 | MVP 范围锁定 | 2 | MVP_SCOPE + USER_STORIES |
| M3 | 脚手架完成 | 3 | 三栈各跑通 + 最小跨栈链路 |
| M4 | 数据模型可用 | 4.1 | Epic 1 全过 |
| M5 | 表单可用 | 4.2 | Epic 2 全过 |
| M6 | 视图可用 | 4.3 | Epic 3 全过 |
| M7 | 权限可用 | 4.4 | Epic 4 全过 |
| M8 | 工作流可用 | 4.5 | Epic 5 全过 |
| M9 | MVP 完成 | 4 | 33 个 P0 全过 + Demo |
| M10 | 优化启动 | 5 | BASELINE + 雷达建立 |
| M11 | 优化收敛 | 6 | 指标全达标 |
| M12 | v1.0 发布 | 7 | TERMINATION + release notes |

---

## 八、每周复盘模板

每个周末写一份 `WEEK_<NN>_REVIEW.md`:

```markdown
# Week NN 复盘

## 完成的故事
- [x] US-XXX
- [x] US-YYY

## 未完成的故事
- [ ] US-ZZZ(原因)

## 阻塞
- (如无,写"无")

## 学到的教训
1. ...

## 下周计划
1. ...

## 风险更新
- 新增: ...
- 缓解: ...
```
