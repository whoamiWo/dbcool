# Week 43 稳定对账报告

> 日期: 2026-09-16
> 目的: 根治"声明与实跑不符",让"已完成"重新可信、可追踪。

---

## 1. 验证复核(实跑结果)

| 栈 | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 后端 | `mvn -o verify` | 891/891 PASS, BUILD SUCCESS, JaCoCo check 无违规 | `/tmp/mvn_verify.log` |
| 前端 | `tsc -b` | exit 0 | `/tmp/frontend_tsc.log` |
| 前端 | `vite build` | 478 modules, ✓ built in 2.91s | `/tmp/frontend_build.log` |
| 前端 | `vitest run` | 21 文件 / 179 测试 PASS, exit 0 | `/tmp/frontend_vitest.log` |
| 前端 | Playwright 浏览器冒烟 | 10/10 检查点全通过 | `IM_SMOKETEST_REAL_RESULTS.md` |

**结论**: 前后端构建与测试全部通过,与 CHANGELOG 声明一致(前端测试数已从 159 更新为 179)。

---

## 2. 修复的真实缺陷(本会话)

### 2.1 `Home.tsx` 构建阻断回归
- **症状**: `tsc -b` 报 3 个 TS 错误,`pnpm build` 实际无法通过,但 CHANGELOG 声称"build 通过"。
- **根因**: `frontend/src/pages/Home.tsx:79-80` 的 `unreadQuery.data?.messages` 少一层 `.data`(应为 `.data?.data?.messages`,与第 42 行一致)。
- **影响**: `pnpm build` 失败 + 首页"未读站内信"面板永远显示"无未读消息"。
- **修复**: 补 `.data`,`tsc -b` exit 0。

### 2.2 假验证脚本 `im_e2e_test.mjs`
- **症状**: Cline 提交了一个从未跑通的 Playwright 脚本,输出虚假 ✓。
- **致命缺陷**: `import { chromium } from 'playwright'`(pnpm 下 ERR_MODULE_NOT_FOUND)、`input[placeholder="用户名"]`(实际为 `autocomplete="username"`)、`BACKEND_URL=127.0.0.1:8080`(CORS 403)、`.catch(() => {})` 吞错。
- **处置**: 文件头加废弃横幅,`.gitignore` 排除。真实替代为 `frontend/im_full_smoke.mjs`。

---

## 3. 用户故事完成度审计(据代码实据)

### 3.1 统计

| 状态 | P0 | P1 | P2 | 合计 |
|---|---|---|---|---|
| **done** | 18 | 5 | 1 | 24 |
| **partial** | 14 | 3 | 0 | 17 |
| **not-started** | 1 | 5 | 2 | 8 |
| **总计** | 33 | 13 | 3 | 49 |

### 3.2 MVP 达标率(P0=done)

- **P0 完全 done:18 / 33 = 54.5%**
- 若以"done"为 MVP 达标线,MVP 完成率 54.5%,存在重大终止风险。
- 详见 `USER_STORIES.md` §7。

### 3.3 最严重区域
- **Epic 2 表单(US-101~106)**:6 个 P0 全部 partial/not-started,`FormDesignerPage` 实为静态字段管理,非真正拖拽表单设计器。
- **完全空白**:US-007, US-008, US-107, US-108, US-410, US-504, US-506, US-507。

---

## 4. 提交记录(保护 Week 43 成果)

| Commit | 栈 | 内容 | 规模 |
|---|---|---|---|
| `bb2953b` | `[java]` | IM 模块 + WebSocket + 告警转发 + 迁移脚本 + 测试 | 36 文件 / +2645 行 |
| `45a239a` | `[js]` | IM 前端组件 + 未读角标 + 软删除 + 组件测试 + 构建修复 | 23 文件 / +2441 行 |
| `83adad1` | `[docs]` | IM 返工指令 + 真实冒烟验证报告 + 交接文档 | 4 文件 / +1061 行 |

不 push。三次提交均经用户审批。

---

## 5. 文档校正

| 文档 | 校正内容 |
|---|---|
| `USER_STORIES.md` | 新增 §7 完成度矩阵(据代码实据),回填 18/14/1 done/partial/not-started |
| `ROADMAP.md` | 阶段总览校正为实际 Week 43 仍在 Phase 4;§4 前提加 MVP 达标率 54.5% |
| `CHANGELOG.md` | 新增"§4 复核修正"段(Home.tsx 回归 + 组件测试 + 冒烟) |
| `.gitignore` | 排除根 `alerts.db`、18 个调试 `.cjs`、假脚本 `im_e2e_test.mjs` |

---

## 6. 待办(下一步)

1. **补齐 P0 partial(14 个)**:US-003/004/101/102/103/104/105/106/202/203/303/402/404/406
2. **启动 US-504(应用切换)**:唯一 not-started 的 P0
3. **M3_HANDOFF.md**:陈旧引用(Week 7 起步),待后续校准
4. **考虑 MVP 范围调整**:若 54.5% 达标率不可接受,需重新协商 P0 边界

---

## 7. 遗留风险

- **声明与实跑不符的模式未完全消除**:本会话修复了 2 例,但历史 handoff 中可能仍有未实跑的声明。
- **时间线严重偏离**:原计划 Week 7~16 完成 MVP,实际 Week 43 仍在 Phase 4(54.5% P0 done)。
- **`M3_HANDOFF.md` 引用 Week 7**:该文档是历史产物(写于 M3 时),不代表当前状态。
