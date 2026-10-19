# 技术债登记

> 最后更新：2026-10-19（PHASE114 全面审计后重写）
>
> 说明：本文件此前仅 19 行且条目全部标记"已解决"，与实际债务规模严重不符。
> 本次按 PHASE114 只读勘察 + 实测门禁的证据重写。

---

## 一、已清偿（PHASE114）

| # | 债务 | 原状态 | 处置 |
|---|------|--------|------|
| 1 | **Controller 方法级鉴权缺口 215 处** | 🔴 高：门禁"冻结基线"假绿 —— `ControllerAuthorizationCoverageTest` 只比对新增未授权项，存量 215 处被基线永久豁免，实测输出「未授权 215 处，均在基线内」却 BUILD SUCCESS | ✅ 已修复：34 处补 `hasRole('ADMIN')`、169 处补 `isAuthenticated()`、14 处合规入白名单；测试改为**棘轮门禁**（`docs/authorization-ratchet.txt`，阈值 0，只降不升，已实证可失败） |
| 2 | 白名单按行号匹配，代码一改即静默失效 | 🟡 中：DingTalkController 经 PHASE113 改过后行号位移，回调白名单失效 → 回调端点重新计入缺口，若据此补注解会导致第三方回调 403 | ✅ 改为按「文件路径 + 方法名」匹配（行号仅作文档用途），并新增失效条目检测 |
| 3 | 白名单含失效条目 | 🟡 低：`swagger/SwaggerController.java:15` 指向全仓不存在的文件却长期被豁免 | ✅ 已删除，并新增「白名单引用的源文件必须存在」校验 |
| 4 | `DynamicTableManager.getNextAutonumber` 标识符未校验 | 🟡 中：`collectionName`/`fieldName` 直接拼接进 `CREATE SEQUENCE` / `nextval` / `extra->>'...'`；该方法为 public，可绕过 `CollectionService.get()` 的库内白名单 | ✅ 已补 `validateIdentifier()` 双重校验 |
| 5 | API Key 三处缺陷 / scopes 存而不校 / 管理端点未鉴权 | 历史 | 已于 2026-09-18 修复 |
| 6 | AlertStore 游离 SQLite / 插件 SPI 空壳 | 历史 | 已接入生产代码 |

---

## 二、存量债务（未清偿）
| 16 | ~~硬编码 hasRole('ADMIN') 81 处~~ ✅ | ~~🟢 低~~ 已修复 | `@PreAuthorize("hasRole('ADMIN')")` 已替换为 `RoleName.ADMIN` 常量 | 见 SECURITY.md §6.1 | 已修复（PHASE115 审计项 #10，2026-10-19） |

| # | 债务 | 优先级 | 证据 | 影响 | 建议 |
|---|------|--------|------|------|------|
| 7 | **JWT 签名密钥有硬编码 dev 默认值** | 🔴 高 | `backend-java/src/main/resources/application.yml:89` `secret: ${JWT_SECRET:dev_jwt_secret_at_least_32_characters_long_for_hs256}`；`KeyRingService.java:80` 仅 warn 不阻断 | compose 路径已被 `${JWT_SECRET:?}` 挡住，但 **dev profile 直跑 Spring Boot 时该公开密钥生效** → 可自签任意 userId/tenantId 的 JWT，击穿租户隔离与鉴权 | 改为无回落（未注入即启动失败），或仅在 test profile 保留 |
| 8 | ~~Python 侧 MinIO 弱口令回落~~ ✅ | ~~🟡 中~~ 已修复 | `backup.py:57` 已移除 `minio123` 默认值，MinIO 分支消费前 fail fast；`conftest.py` 注入 CI 专用密码 | 见 SECURITY.md | 已修复（PHASE114 后续，2026-10-19） |
| 9 | ~~WebSocket Origin 全开~~ ✅ | ~~🟡 中~~ 已修复 | `WebSocketConfig.java:27`、`HuddleWebSocketConfig.java:42`、`ImWebSocketConfig.java:48` 原均 `setAllowedOriginPatterns("*")`，现已收敛为 `app.cors.allowed-origins` 白名单 | 任意来源可建连 | 已修复（PHASE114 后续，2026-10-19） |
| 10 | ~~安全响应头缺失~~ ✅ | ~~🟡 中~~ 已修复 | 已在 `frontend/nginx.conf.template` + `render_nginx.py`（443 块）补 `X-Frame-Options`/`X-Content-Type-Options`/`Referrer-Policy`/`CSP`，`/assets/` 防继承阻断 | 见 SECURITY.md PHASE115 | 已修复（2026-10-19，CSP `unsafe-inline/eval` 收敛待前端改造） |
| 11 | ~~CORS 配置漂移~~ ✅ | ~~🟡 低~~ 已修复 | `SecurityConfig` 已改为读取 `app.cors.allowed-origins`（默认 localhost），WebSocket 三处同源 | 见 SECURITY.md | 已修复（PHASE114 后续，2026-10-19） |
| 12 | **Spring Boot 3.3.5 已 EOL** | 🟡 低 | `backend-java/pom.xml:7-10` | 3.3.x 分支停止 OSS 支持，安全补丁不再回溯 | 排期升级 3.4/3.5 |
| 13 | **动态 SQL 标识符校验不完整** | 🟡 低 | `DynamicTableManager` 仅校验 columnName，**不校验 collectionName**（依赖调用方先查元数据）；`getNextAutonumber` 已补，其余路径仍靠 `CollectionService.get()` 库内白名单 | 新增调用方绕过即成注入点 | 在 `DynamicTableManager` 入口统一校验 |
| 14 | **文档债务：228 个临时任务书未归档** | 🟡 低 | 根目录 232 个 md，其中 `PHASE*_PROMPT` 86、`PHASE*_TASKS` 64、`WEEK_*_HANDOFF` 36、`PHASE*_REWORK*` 42；`docs/archive/` 仅 1 个文件 | 仓库噪音，新人难辨有效文档 | 移入 `docs/archive/` |
| 15 | 遗留调试容器 | 🟢 极低 | `dreamy_colden` 容器 unhealthy | 干扰 `docker ps` 健康判断 | 清理 |

---

## 三、审计结论索引

- 完整审计：`docs/PHASE113_AUDIT_REPORT.md`
- 鉴权修复：本次 PHASE114（棘轮门禁 + 203 处注解 + 14 条白名单）
- 门禁现状：Java 2225/0F/0E、E2E 135/135、合规 16/16、备份 30/30、编排 8/8、冒烟 11/11
