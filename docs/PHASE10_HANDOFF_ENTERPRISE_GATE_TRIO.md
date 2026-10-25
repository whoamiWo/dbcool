# PHASE10 交接文档 — 企业落地硬门槛三件套

> **用途**：本文件为**跨会话/跨模型交接**而写。若当前会话 token 用尽，接手模型**只需读本文件 + 按「六、接手第一步」执行命令**，即可从任意断点继续，无需重新探查代码。
>
> **基线**：`main` @ v2.0.0（commit `01db233`），工作树 clean
> **创建时间**：2026-10-25
> **任务来源**：对标 NocoDB / NocoBase / Slack / RocketChat / Trello / Airtable / Notion 后，确定补齐企业准入门槛

---

## 一、任务目标（一句话）

实现**企业落地硬门槛三件套**，形成完整业务闭环：

```
进得去（OIDC SSO） → 用得起来（公开表单免登录填写） → 出得来（Excel/PDF 导出）
```

三件套补齐后，平台可替代企业现有「表单工具 + 报表工具」，并满足 IT 部门准入要求。

### 1.1 功能边界（本次不做，避免范围蔓延）

| 不做 | 原因 |
|---|---|
| SAML | 仅做 OIDC，覆盖绝大多数现代 IdP（Keycloak/Azure AD/Okta/AD FS） |
| 导出模板自定义 | 先用固定表头/列宽样式 |
| 导出异步任务 | 同步返回，数据量沿用现有 listRecords limit |
| 公开表单验证码/限流 | 后续迭代 |
| 前端登录页改造 | OIDC 走后端重定向，前端零改造（见决策 1） |

---

## 二、当前状态

### 2.1 已完成（前置，无需重做）

- ✅ v2.0.0 正式发布（tag + CHANGELOG + README + ROADMAP M30）
- ✅ PHASE9 一键部署闭环：`deploy.sh from-scratch` 实测 EXIT=0
- ✅ 49/49 用户故事全部完成
- ✅ 门禁全绿：Java 2284 tests / 前端 885 tests / 覆盖率 82.7%

### 2.2 三件套状态：**全部未开始**（0/6 todo）

| todo id | 任务 | 状态 |
|---|---|---|
| `oidc-sso` | OIDC SSO（后端） | ⏳ pending |
| `public-form-backend` | 公开表单后端 | ⏳ pending |
| `public-form-frontend` | 公开表单前端页 | ⏳ pending（依赖 public-form-backend） |
| `export-python` | Excel/PDF 导出（Python） | ⏳ pending |
| `export-java-frontend` | 导出 Java 编排 + 前端按钮 | ⏳ pending（依赖 export-python） |
| `integration-verify` | 三件套集成验证 | ⏳ pending（依赖以上全部） |

### 2.3 建议执行顺序（依赖已标注）

```
并行起点：① oidc-sso  ┐
          ② public-form-backend ├─→ ④ public-form-frontend
          ③ export-python      ┘─→ ⑤ export-java-frontend
                                    ↓
                              ⑥ integration-verify（需 ①④⑤ 完成）
```

---

## 三、关键技术决策（接手者必须遵守，勿自行更改）

### 决策 1：OIDC 用 `oauth2Login`（授权码流程），**不用** `oauth2ResourceServer`

**原因**：现有认证是自家 JWT（`JwtAuthFilter` 解析 HS256），前端各处依赖 `/api/auth/login` 返回的 JWT。若改用 `oauth2ResourceServer`（校验 IdP 签发的 JWT），需改造所有前端请求的 token 来源，爆炸半径过大。

**做法**：`oauth2Login` 走授权码流程，`OidcLoginSuccessHandler` 在认证成功后**签发本地 JWT**，重定向回前端并带 token。
- 前端零改造（继续用本地 JWT）
- IdP 只做身份验证，权限/租户/审计仍由本地 SSOT 掌控
- 与钉钉/飞书 OAuth 既有模式一致（外部认证 → 换发本地 JWT）

### 决策 2：公开表单复用 `WikiPageService.share()` 的 shareToken 成熟模式

不新造模式，直接移植。**参照代码**（`backend-java/src/main/java/com/nocobase/wiki/WikiPageService.java` L337-361）：

```java
/** 生成分享链接 token（免登录只读访问）。 */
@Transactional
public WikiPageEntity share(UUID id, boolean regenerate, String tenantId) {
    WikiPageEntity e = get(id);
    if (!e.getTenantId().equals(tenantId)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
    }
    if (regenerate || e.getShareToken() == null) {
        e.setShareToken(UUID.randomUUID().toString().substring(0, 32));
    }
    return pageRepository.save(e);
}

/** 撤销分享链接。 */
@Transactional
public WikiPageEntity unshare(UUID id, String tenantId) {
    WikiPageEntity e = get(id);
    if (!e.getTenantId().equals(tenantId)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
    }
    e.setShareToken(null);
    return pageRepository.save(e);
}
```

### 决策 3：导出由 Python 生成文件，Java 负责编排

**原因**：`ARCHITECTURE.md` 明确「报表导出(PDF/Excel)」属 Python 职责；且 openpyxl/reportlab 在 Python 生态最成熟。Java 不直连导出库。

**数据流**：
1. Java `/api/collections/{name}/export?format=xlsx|pdf` — 复用 `exportCsv` 的权限/租户/审计逻辑取数据
2. Java 调 Python `/api/export/xlsx` 或 `/api/export/pdf`（内部 HTTP，复用 `AiAssistantService` 的 `pythonUrl + RestTemplate` 模式）
3. Python 用 openpyxl/reportlab 生成字节流返回，Java 透传 `ResponseEntity<byte[]>`

---

## 四、任务详细实施说明

### 任务 ①：OIDC SSO（`oidc-sso`）

**现状核验**：
- `backend-java/pom.xml` `grep oauth2` = **0 命中**；仅有 `spring-security-test`（L202）
- 现有认证：JWT (`JwtAuthFilter`) + API Key (`ApiKeyFilter`) + 钉钉/飞书自有 OAuth
- `SecurityConfig.java` L65-151 为 securityFilterChain，公开端点 `permitAll()`，`anyRequest().authenticated()`

**改动清单**：

| 文件 | 动作 | 说明 |
|---|---|---|
| `backend-java/pom.xml` | MODIFY | 新增 `spring-boot-starter-oauth2-client`、`spring-security-oauth2-jose`（Spring Boot 3.4.7 parent 管理版本，无需指定版本） |
| `backend-java/src/main/resources/application.yml` | MODIFY | 在 `app:` 节下新增 `oidc:` 配置（见下方配置示例） |
| `backend-java/src/main/java/com/nocobase/auth/oidc/OidcProperties.java` | NEW | `@ConfigurationProperties("app.oidc")` 绑定配置 |
| `backend-java/src/main/java/com/nocobase/auth/oidc/OidcUserService.java` | NEW | OIDC 用户映射 / JIT 建号 |
| `backend-java/src/main/java/com/nocobase/auth/oidc/OidcLoginSuccessHandler.java` | NEW | 认证成功 → 签发本地 JWT → 重定向 |
| `backend-java/src/main/java/com/nocobase/config/SecurityConfig.java` | MODIFY | 加 `oauth2Login`；放行 `/oauth2/**`、`/login/oauth2/**` |
| `backend-java/src/test/java/com/nocobase/auth/oidc/OidcUserServiceTest.java` | NEW | 用户映射 / JIT 建号测试 |

**配置示例**（写入 `application.yml` 的 `app:` 节内，与 `dingtalk:` / `jwt:` 同级）：

```yaml
  # OIDC 通用 SSO（企业身份源：Keycloak / Azure AD / Okta / AD FS）
  # 未配置 client-id 时 OIDC 自动禁用（不注册 ClientRegistration），不影响既有登录
  oidc:
    enabled: ${OIDC_ENABLED:false}
    # IdP 发现地址，例 Keycloak: http://keycloak:8080/realms/{realm}
    issuer-uri: ${OIDC_ISSUER_URI:}
    client-id: ${OIDC_CLIENT_ID:}
    client-secret: ${OIDC_CLIENT_SECRET:}
    scope: ${OIDC_SCOPE:openid,profile,email}
    # 登录成功后重定向前端的地址（带 ?token=）
    success-redirect: ${OIDC_SUCCESS_REDIRECT:http://localhost:5173/oidc/callback}
```

**JWT 签发契约**（复用现有 `JwtService`，勿新建签发逻辑）：

```java
// backend-java/src/main/java/com/nocobase/auth/JwtService.java L51
public String issueAccessToken(UUID userId, String username, String tenantId, List<String> roles)
// claims: username / tid / typ="access" / roles；header 含 kid；HS256
```

**`OidcLoginSuccessHandler` 伪代码契约**：

```java
// 认证成功后：
// 1. 从 authentication 取 OidcUser → 拿 sub / email / name
// 2. 调 oidcUserService.mapOrCreate(sub, email, name) → 返回本地 UserEntity + roles
// 3. 调 jwtService.issueAccessToken(userId, username, tenantId, roles) → 本地 JWT
// 4. response.sendRedirect(successRedirect + "?token=" + jwt)
```

**验证**：
```bash
cd backend-java && mvn -o test -Dtest='OidcUserServiceTest' -q
# 且全量测试不回归：mvn -o test（2284+ 应全绿）
```

---

### 任务 ②：公开表单后端（`public-form-backend`）

**现状核验**：
- `FormEntity.java` **无** shareToken（对比 `WikiPageEntity.java` L86-87 有 `@Column(name="share_token", length=64, unique=true) private String shareToken`）
- `FormController.java` 仅 5 端点：`@RequestMapping("/api/forms")` + POST / GET / GET{id} / PUT{id} / DELETE{id}
- 表 `forms` 无 `share_token` 列 → 需 Flyway 迁移

**改动清单**：

| 文件 | 动作 | 说明 |
|---|---|---|
| `backend-java/src/main/resources/db/migration/V65__form_share_token.sql` | NEW | 加列 + 唯一索引 |
| `backend-java/src/main/java/com/nocobase/form/FormEntity.java` | MODIFY | 加 `shareToken` 字段 + getter/setter |
| `backend-java/src/main/java/com/nocobase/form/FormService.java` | MODIFY | 加 `share` / `unshare` / `getByShareToken` / `submitPublic` |
| `backend-java/src/main/java/com/nocobase/form/FormController.java` | MODIFY | 加 4 个端点（见下） |
| `backend-java/src/main/java/com/nocobase/config/SecurityConfig.java` | MODIFY | 放行 `/api/forms/public/**` |
| `backend-java/src/test/java/com/nocobase/form/FormPublicSubmitTest.java` | NEW | 分享/撤销/匿名提交测试 |

**迁移脚本** `V65__form_share_token.sql`（H2 兼容，参照 V61/V64 约定用 `IF NOT EXISTS`）：

```sql
-- US: 公开表单分享（PHASE10 企业门槛三件套）
-- 复用 WikiPageEntity.share_token 的设计：UUID 前 32 位，唯一索引，可置 null 撤销
ALTER TABLE forms ADD COLUMN IF NOT EXISTS share_token VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS idx_forms_share_token ON forms (share_token);
```

**新增端点契约**：

```
POST   /api/forms/{id}/share          → 生成/返回 shareToken（认证 + tenant 校验）
DELETE /api/forms/{id}/share          → 撤销分享（shareToken 置 null）
GET    /api/forms/public/{token}      → 免登录，按 token 取表单定义（token 无效 → 404，不泄露存在性）
POST   /api/forms/public/{token}/submit → 免登录，匿名提交落 Collection（返回 recordId）
```

**服务方法契约**：

```java
public interface FormPublicService {
    FormEntity getByShareToken(String token);                  // 无效 → ResponseStatusException(404)
    UUID submitPublic(String token, Map<String, Object> data); // 匿名提交，落对应 Collection
    FormEntity share(UUID formId, String tenantId, UUID userId);
    FormEntity unshare(UUID formId, String tenantId);
}
```

**安全红线**（沿用 SecurityConfig L76-96 的既有约定）：
- 公开端点放行**不等于裸奔**：`public/{token}` 必须查表校验 token，无效返回 404（不返回 403，避免泄露表单是否存在）
- 所有查询带 `tenantId` 过滤（share/unshare 必须校验 tenant，参照 Wiki 实现）
- share/unshare 记 `AuditService`

**验证**：
```bash
cd backend-java && mvn -o test -Dtest='FormPublicSubmitTest' -q
```

---

### 任务 ③：公开表单前端（`public-form-frontend`）— 依赖 ②

| 文件 | 动作 | 说明 |
|---|---|---|
| `frontend/src/pages/FormPublicFill.tsx` | NEW | 公开填写页（免登录） |
| `frontend/src/router.tsx` | MODIFY | 加 `/forms/public/:token` 免认证路由 |

**页面设计**（沿用现有 `FormRuntime.tsx` 的渲染模式与 MUI 主题，不引入新设计语言）：

- **顶部**：表单标题（h5）+ 描述（body2 次级色）；无导航/无侧边栏
- **主体**：按 `layoutJson` 渲染字段，沿用 24 栅格 span 规则，字段间距 16px
- **底部**：提交按钮（主色实心），提交后展示成功态卡片
- **异常态**：token 无效 → 居中错误卡片「表单不存在或链接已失效」
- **响应式**：单列，最大宽度 720px 居中；窄屏（<768px）内边距收敛为 16px

**字体/色彩**（项目 design_guidelines）：
- 字体 `PingFang SC`；标题 20px/600，副标题 14px/400，正文 14px/400
- 主色 `#1976D2` / `#1565C0`；背景 `#FFFFFF` / `#F5F7FA`；文字 `#212121` / `#757575`
- 功能色：成功 `#4CAF50`、错误 `#F44336`、警告 `#FF9800`

**验证**：
```bash
cd frontend && npx tsc -b && npx vitest run --reporter=dot 2>&1 | tail -5
```

---

### 任务 ④：Excel/PDF 导出 — Python 侧（`export-python`）

**现状核验**：
- `backend-python/pyproject.toml` dependencies **无** openpyxl / reportlab
- Python routers 目录：`ai.py / alerts.py / backup.py / connectors.py / health.py / integration.py / tasks.py`（**无 export.py**）
- `main.py` 路由注册在 `create_app()`：`app.include_router(xxx.router)`（L195-204）

**改动清单**：

| 文件 | 动作 | 说明 |
|---|---|---|
| `backend-python/pyproject.toml` | MODIFY | dependencies 加 `openpyxl>=3.1.0`、`reportlab>=4.0.0` |
| `backend-python/src/nocobase_py/routers/export.py` | NEW | `POST /api/export/xlsx`、`POST /api/export/pdf` |
| `backend-python/src/nocobase_py/services/export_service.py` | NEW | openpyxl / reportlab 生成逻辑 |
| `backend-python/src/nocobase_py/main.py` | MODIFY | import export + `app.include_router(export.router)` |
| `backend-python/tests/test_export.py` | NEW | xlsx / pdf 生成测试 |

**路由契约**（Python 侧，接收 Java 传来的数据，不直连数据库）：

```python
# POST /api/export/xlsx   Content-Type: application/json
# 请求体：{"headers": ["col1","col2"], "rows": [[v1,v2], ...], "title": "报表名"}
# 响应：application/vnd.openxmlformats-officedocument.spreadsheetml.sheet（字节流）

# POST /api/export/pdf
# 请求体：同上
# 响应：application/pdf（字节流）
```

**main.py 注册位置**（在 `create_app()` 内，与既有 include_router 并列）：

```python
from nocobase_py.routers import ai, alerts, backup, connectors, health, integration, tasks, export
...
    app.include_router(export.router)   # 加在 alerts 之前或之后均可
```

**验证**：
```bash
cd backend-python && uv sync --all-extras && uv run pytest tests/test_export.py -q
```

---

### 任务 ⑤：导出 Java 编排 + 前端入口（`export-java-frontend`）— 依赖 ④

| 文件 | 动作 | 说明 |
|---|---|---|
| `backend-java/src/main/java/com/nocobase/export/ExportFileService.java` | NEW | 调 Python（复用 `AiAssistantService` 的 `pythonUrl + RestTemplate` 模式） |
| `backend-java/src/main/java/com/nocobase/meta/CollectionController.java` | MODIFY | `exportCsv` 旁加 `exportFile(format)` |
| `frontend/src/pages/TableView.tsx` | MODIFY | 导出按钮增加 xlsx / pdf 选项 |

**Java 编排必须复用的 `exportCsv` 全流程**（`CollectionController.java` L592-641，**逐行参照**）：

```java
@PreAuthorize("isAuthenticated()")
@GetMapping(value = "/{name}/export", produces = "text/csv;charset=UTF-8")
public ResponseEntity<String> exportCsv(
        @PathVariable String name,
        @RequestParam(defaultValue = "1000") int limit,
        @AuthenticationPrincipal AuthenticatedUser user) {
    // 1. ACL 权限校验
    aclEnforcer.assertCan(user.userId(), user.tenantId(), name, AclPolicyEntity.Action.READ);
    // 2. 取 meta + records
    CollectionMetaEntity meta = service.get(name, user.tenantId());
    List<Map<String, Object>> records = service.listRecords(name, user.tenantId(), limit);
    // 3. 字段级过滤 + 行级过滤
    records = records.stream()
            .map(r -> aclEnforcer.filterRecord(user.userId(), user.tenantId(), name, r)).toList();
    records = rowAclService.filterReadable(user.tenantId(), name, records, rowAclPrincipal(user));
    // 4. 解析 fields_json 得到 headers（失败记 WARN，不静默）
    List<String> headers = ...; // ObjectMapper 读 meta.getFieldsJson()，取每个 f.get("name")
    // 5. 构建内容（CSV 用 StringBuilder escapeCsv）
    // 6. 审计
    auditService.log(user.tenantId(), user.userId().toString(), user.username(),
            "collection.export_csv", "collection", name,
            Map.of("collection_name", name, "exported_rows", records.size(), "filter", "limit=" + limit));
    // 7. 返回 ResponseEntity + Content-Disposition
    return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(csv.toString());
}
```

**`exportFile` 与 `exportCsv` 的差异**：步骤 1-4、6 完全相同；步骤 5 改为「把 headers + rows 组装成 JSON，POST 给 Python `/api/export/{format}`，拿回 `byte[]`」；步骤 7 的 `produces` 与扩展名按 format 变化（`xlsx` → `.xlsx` / `pdf` → `.pdf`），审计 action 改为 `collection.export_file` 并记录 `format`。

**前端**：`TableView.tsx` 现有导出按钮（参照 `exportCsv()` L202 + 触发按钮 L265）旁增加下拉：CSV（现有）/ Excel / PDF；请求期间按钮 loading 禁用。

**验证**：
```bash
cd backend-java && mvn -o test -q   # 全绿
cd frontend && npx tsc -b && npx vitest run --reporter=dot 2>&1 | tail -5
```

---

### 任务 ⑥：三件套集成验证（`integration-verify`）

```bash
# 1. 后端全量（Java）
cd /home/who/multistack-project/backend-java && mvn -o test 2>&1 | grep -E "Tests run:|BUILD"

# 2. Python
cd /home/who/multistack-project/backend-python && uv run pytest -q

# 3. 前端类型 + 测试
cd /home/who/multistack-project/frontend && npx tsc -b && npx vitest run --reporter=dot 2>&1 | tail -5

# 4. 前端构建
cd /home/who/multistack-project/frontend && pnpm build

# 5. 一键部署冒烟（可选，需 Docker）
cd /home/who/multistack-project && ./deploy.sh preflight && ./deploy.sh verify
```

**验收标准**：
- Java 测试全绿（≥2284，新增测试后更多）
- Python 测试全绿（含新增 test_export.py）
- 前端 `tsc -b` 0 error、`vitest` 全绿、`pnpm build` 成功
- 公开提交链路可跑通（curl 匿名 POST `/api/forms/public/{token}/submit`）
- 导出链路可跑通（curl 带 JWT GET `/api/collections/{name}/export?format=xlsx`）

---

## 五、关键文件速查表

| 用途 | 路径 |
|---|---|
| Security 配置（改 oauth2Login + 放行） | `backend-java/src/main/java/com/nocobase/config/SecurityConfig.java` |
| JWT 签发（复用，勿新建） | `backend-java/src/main/java/com/nocobase/auth/JwtService.java` L51 |
| shareToken 参照实现 | `backend-java/src/main/java/com/nocobase/wiki/WikiPageService.java` L337-361 |
| 表单实体（加字段） | `backend-java/src/main/java/com/nocobase/form/FormEntity.java` |
| 表单控制器（加端点） | `backend-java/src/main/java/com/nocobase/form/FormController.java` |
| 导出 CSV 参照 | `backend-java/src/main/java/com/nocobase/meta/CollectionController.java` L592-641 |
| Python 路由注册 | `backend-python/src/nocobase_py/main.py` L195-204（`create_app()`） |
| Python 依赖 | `backend-python/pyproject.toml` |
| Flyway 迁移目录 | `backend-java/src/main/resources/db/migration/`（已有 V64） |
| 前端路由 | `frontend/src/router.tsx` |
| 表格页导出按钮 | `frontend/src/pages/TableView.tsx`（exportCsv L202 / 按钮 L265） |
| 应用配置 | `backend-java/src/main/resources/application.yml`（`app:` 节 L83） |

---

## 六、接手第一步（复制执行）

```bash
# 1. 进入项目并确认基线
cd /home/who/multistack-project
git log --oneline -1          # 应为 01db233 release(v2.0.0)...
git status --short            # 应 clean（或仅有本次三件套改动）

# 2. 读本文件确认进度
cat docs/PHASE10_HANDOFF_ENTERPRISE_GATE_TRIO.md

# 3. 查 todo 状态（从以下三条并行起点任选，或全部开始）
#    ① oidc-sso    ② public-form-backend    ③ export-python

# 4. 开始编码（按「四、任务详细实施说明」对应章节）
```

---

## 七、约束红线（违反会导致 CI 失败或安全漏洞）

1. **多租户**：任何新增查询必须带 `tenantId` 过滤；`share/unshare` 必须校验 tenant（参照 Wiki）
2. **公开端点**：`/api/forms/public/**` 放行后必须有 token 查表校验；无效返回 **404**（非 403），不泄露资源存在性
3. **审计**：share/unshare、导出操作必须记 `AuditService`（与 exportCsv 一致）
4. **不直连库**：Python 不连 PostgreSQL；导出只接收 Java 传来的数据
5. **依赖版本**：Spring Boot 3.4.7 parent 管理 oauth2 版本，**不要手动指定版本**
6. **测试**：每个任务都有对应测试文件（OidcUserServiceTest / FormPublicSubmitTest / test_export.py），不可跳过
7. **前端**：类型检查 `tsc -b` 必须 0 error；新增页面沿用 MUI + 现有 FormRuntime 模式

---

## 八、完成后收尾

三件套全部完成且验证通过后：
1. 更新 `CHANGELOG.md`（新增 v2.1.0 条目，描述三件套）
2. 更新 `README.md`（核心能力加：OIDC SSO / 公开表单 / Excel-PDF 导出）
3. 更新 `USER_STORIES.md`（若新增对应故事）
4. 提交 + 推送 + 打 tag `v2.1.0`（流程参照 v2.0.0：commit → push → `git tag -a` → push tag）
5. 创建 GitHub Release（Release Notes 放 `docs/` 或直接在网页填）

---

**文档结束**。接手模型按第六节开始即可，无需重新探查。
