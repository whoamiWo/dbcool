# ADR-010: Week 0~9 期间修复的真实 Bug 清单

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **作者**: Cline (记录自历次会话踩坑)

---

## 背景

本项目从零开发到能跑通的 9 周过程中,踩过 8+ 个真实 bug。本 ADR 列出每个 bug 的现象、根因、修复、预防措施。

---

## Bug-001: StringRedisTemplate 找不到

- **现象**: Java 启动报错 package org.springframework.data.redis.core does not exist
- **根因**: RefreshTokenService 用了 StringRedisTemplate,但 pom.xml 只引了 spring-boot-starter-data-jpa,没引 Redis starter
- **修复**: 加 spring-boot-starter-data-redis 依赖
- **预防**: 凡是用 Spring Data X 的模块都要确认 starter 是否齐全

## Bug-002: Map.of() 不允许 null

- **现象**: NPE 在 Map.of 构造时(MapN 拒绝 null)
- **根因**: Java 9 的 Map.of/k 不接受 null 值,但 controller 的 toDto 把可能为 null 的字段塞进去
- **修复**: 改用 new HashMap<>() + put()
- **预防**: DTO 序列化时统一用可变 Map

## Bug-003: UUID 列类型不匹配

- **现象**: ERROR: column "id" is of type uuid but expression is of type character varying
- **根因**: JDBC INSERT ... VALUES (?, ?) 占位符 ? 没有显式类型,PG 推断为 varchar
- **修复**: SQL 改为 INSERT ... VALUES (?::uuid, ?::jsonb)
- **预防**: 涉及特殊类型列的 JDBC 操作一律加 ::type 强转

## Bug-004: toDto() 返回不可变 Map 后 .put() 抛 UOE

- **现象**: UnsupportedOperationException: ImmutableCollections.uoe
- **根因**: Map.of(...) 返回的 immutable Map 不能 put,但代码先 toDto 拿到 immutable Map,再 dto.put("fields", ...)
- **修复**: 同 Bug-002,改 HashMap
- **预防**: toDto 一律返回可变 Map

## Bug-005: Spring Security 业务异常被吞

- **现象**: 业务抛 ResponseStatusException(404/409),但客户端收到 {"code":1001,"message":"???"}
- **根因**: ExceptionTranslationFilter 拦截了业务异常,因为没设 AuthenticationEntryPoint,NPE 进了 try-catch
- **修复**:
  1. SecurityConfig 加 exceptionHandling().authenticationEntryPoint(...)
  2. 新建 @ControllerAdvice GlobalExceptionHandler 接管 ResponseStatusException
- **预防**: Spring Boot 3.x + Spring Security 6.x 项目必须显式定义 exception handlers

## Bug-006: renameField 死循环 409

- **现象**: 字段 email 重命名为 email_X 报 "目标字段名已存在"
- **根因**: 检查 anyMatch(f.name == newName && f.name != oldName) — 但 list 里 oldName 已被替换为 newName,所以 f.name != oldName 永远 true
- **修复**: 先 filter(... == newName).count() > 0 检查(在替换前),再替换
- **预防**: list 修改后任何条件判断都要意识到 list 已变

## Bug-007: TypeScript ApiResponse<T> 误用

- **现象**: 前端 build 报 80+ TS 错误,property X does not exist on ApiResponse<T>
- **根因**: axios 拦截器已经 response => response.data 解了一层,但代码又 apiClient.get<T>().data.data 多取一层
- **修复**:
  1. 重写 apiClient wrapper:get/post/etc<T> 直接返回 T
  2. 全局替换 .data.data → .data
- **预防**: 团队约定一种返回风格,不要 axios 拦截 + 手动 .data 同时用

## Bug-008: WSL DNS 劫持 + SSH 22 临时被封

- **现象**: ping github.com 通但实际是 127.0.0.1;git push 失败 Connection refused
- **根因**: WSL 上游 DNS 解析把 github.com 解析成 127.0.0.1(校园网/公司网劫持);SSH 22 端口被网络层拦截
- **修复**:
  1. /etc/hosts 加 140.82.112.3 github.com
  2. HTTPS 走 PAT;SSH 走 22 IP 直连
- **预防**: WSL 环境下 /etc/hosts 必须固化 GitHub 解析

---

## 共同模式总结

1. **Java 不可变集合**: 不要对 Map.of() 结果调用 put()
2. **JDBC 类型推断**: 写 SQL 时永远显式 ?::uuid、?::jsonb
3. **Spring Security 异常处理**: 必须配置 AuthenticationEntryPoint + AccessDeniedHandler
4. **TypeScript 类型与运行时**: axios 拦截器改 response 后,类型注解也要同步改
5. **WSL 网络**: hosts 必固化,SSH 不通就用 HTTPS+PAT
