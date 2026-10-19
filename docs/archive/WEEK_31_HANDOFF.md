# Week 31 Handoff — 全包收尾:AuditService + AuditController + ViewService + JwtService + MessageController + 红线抬升

> Date: 2026-09-14 · 验证: `mvn verify` BUILD SUCCESS · **405 tests PASS** (+39 vs W30 390)

## 1. 目标

按用户推荐组合 A + B + C + D(全收尾方案):
- **A**:AuditService + AuditController — 收尾 audit 包
- **B**:ViewService + JwtService — 收尾 view/auth
- **C**:MessageController — 收尾 workflow
- **D**:抬 4 条 Jacoco 红线

## 2. 完成

### 测试新增(4 个,共 51 tests)
| Test class | tests | 覆盖目标 |
|---|---|---|
| `AuditServiceTest` | **12 PASS** | log 全部路径(IP/User-Agent/serializefail/repo throws/find/count) |
| `AuditControllerTest` | **4 PASS** | list(带/不带 filters) + log DTO 字段完整 |
| `ViewServiceTest` | **14 PASS** | create/update/get/listByCollection/listAll/delete + parseConfig |
| `JwtServiceTest` | **9 PASS** | issue/parse round-trip + 错误 token 返回 null + 短 secret 异常 |
| `MessageControllerTest` | **12 PASS** | list(cursor/unreadOnly/limit/empty) + markRead(成功/404/错 recipient) |

### 红线抬升
| Rule | W30 → W31 | 当前实绩 |
|---|---|---|
| **BUNDLE** | 0.70 → **0.75** | **81%** ✅ |
| **auth** | 0.70 → **0.75** | **81%** ✅ |
| **audit** | 0.80 → **0.90** | **99%** ✅ |
| **view** | 0.90 → **0.95** | **98%** ✅ |
| 其他 (meta/workflow/notification/acl) | 不变 | 全部达标 |

### 覆盖率
| 包 | W30 | W31 | Δ |
|---|---|---|---|
| **audit** | 83% | **99%** | **+16%** 🎯🎯 |
| **view** | 94% | **98%** | +4% |
| **workflow** | 92% | **93%** | +1%(MessageController) |
| **bundle** | 80% | **81%** | +1% |
| auth | 81% | **81%** | — |
| 其他 | — | — | — |

**405 tests**(390 → 405,+39)

## 3. 关键踩坑

### AuditService
1. **`RequestContextHolder` 必须清理**:用 `@AfterEach` 调 `resetRequestAttributes()`,否则多个测试间 request context 污染
2. **`log_serializeFailure_fallsBackToString`**:payload 含自引用会抛 JsonProcessingException,fallback 到 `String.valueOf(payload)`
3. **`log_repoThrows_silentlySwallowed`**:audit 失败不能影响业务,这是设计原则
4. **`log_truncatesLongUserAgent`**:250 字符上限,超过截断
5. **`log_prefersXForwardedForOverRemoteAddr`**:XFF 多段取第一段

### ViewService
1. **`ViewEntity.Type` enum 没有 GRID**:enum 只有 `TABLE / KANBAN / DETAIL`,不是 GRID
2. **null configJson fallback**:`null || isBlank()` → "{}";同样 sharedWithJson
3. **parseConfig 失败抛 RuntimeException**,不是 ResponseStatusException
4. **update 部分字段**:null 参数不覆盖,只更新非 null 字段

### JwtService
1. **`shortSecret` 抛 IllegalStateException**,不是 IllegalArgumentException,message 含 "≥ 32 字节"
2. **`parseAccessToken` 必须 type=access**:手签 `typ=refresh` 也会被拒
3. **round-trip 测签名验证**:签发 + 同 secret 解析;不同 secret 解析 → null
4. **过期 token**:`expiration(Date)` 设过去时间,parse 返回 null(由 Jwts 库抛 ExpiredJwtException,catch 后返回 null)
5. **`getAccessTtl`**:直接读 accessTtl 字段,与 Spring `@Value` 注入的默认值一致

### MessageController
1. **`limit` clamp 范围**:`Math.max(1, Math.min(limit, 100))` — 9999→100, 0→1
2. **`next_cursor` 是空结果时空字符串**:result 不为空时是最后一条 createdAt;result 为空时 ""
3. **`cursor` 解析失败**:`Instant.parse(before)` 抛 DateTimeParseException,catch 后返回 code=400
4. **`markRead` 跨用户**:`m.getRecipient().equals(user.userId())` — 不匹配返回 404(防信息泄漏,同 ROW ACL)
5. **`unreadOnly` 与 cursor 组合**:4 种组合都要分别测

## 4. 候选(Week 32+)

按"原计划"剩余方向:
- **未测大件**:
  - `UserAdminService`(auth, 128 行 0% — 仍 exclude,但有 9 个方法,可分批测)
  - `CollectionService`(meta, 339 行 15% — 太大,继续跳过)
  - `JwtAuthFilter`(auth, 67 行 0% — 中间件,继续跳过)
- **低覆盖包**:
  - config 28%(SecurityConfig 0% / OpenApiConfig 0% / AsyncConfig 0% — 几乎都是配置类)
  - meta 58% 主要是 CollectionService + AsyncMigrationService,均已决定跳过
- **抬红线**:
  - BUNDLE 81% 可再抬 0.80
  - workflow 93% 可再抬 0.85
  - audit 99% 可再抬 0.95
  - view 98% 可再抬 0.97

## 5. Git

```
$ git log --oneline -3
<pending> Week 31: AuditService+ViewService+JwtService+MessageController 全收尾 + 4 红线抬升
fa7b9cd Week 30: 3 NotificationDispatcher + GlobalExceptionHandler + RefreshTokenService + 红线 BUNDLE 0.70/notification 0.85
9dfbb55 Week 29: WorkflowController + WorkflowTemplateService + workflow 红线大跳
```
