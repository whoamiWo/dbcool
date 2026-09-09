# ADR-003: AI / 集成层使用 Python 3.12 + FastAPI

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: Python

## 背景

需要构建:
- LLM 接入(自然语言生成表单、智能分类、字段推荐)
- 第三方数据源连接器
- 异步耗时任务(报表导出、批量操作)
- Webhook 接收与分发

## 决策

- **Python 3.12**
- **FastAPI**(异步、Pydantic 校验、自动 OpenAPI)
- **Pydantic v2**
- **SQLAlchemy 2.0 async + asyncpg**(Python 侧只读 task_result 表)
- **Celery + Redis**(异步任务队列)
- **LangChain / LlamaIndex**(LLM 编排)
- **httpx**(HTTP 客户端,异步)
- **pytest + httpx + factory_boy + respx**
- **ruff + mypy + bandit + coverage.py**

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| Django + DRF | 生态成熟 | 同步为主、ORM 重 | 异步场景不友好 |
| Flask | 简单 | 需自己组装 | 工作量大 |
| Node.js (NestJS) | 与前端同语言 | LLM 生态远不如 Python | AI 库选择面窄 |

## 后果

### 正面
- LLM 生态最好(LangChain / LlamaIndex / OpenAI / Anthropic SDK 都 Python 优先)
- FastAPI 异步原生,适合 IO 密集任务
- Pydantic v2 性能极佳(用 Rust 实现)
- 与 Java 通过 `/internal/*` 解耦

### 负面
- 类型注解代码量比 Java 多
- 部署需考虑 Python 版本管理(用 uv / poetry)
- 运行时性能不如 Java,需靠异步弥补

### 缓解措施
- 所有耗时操作走 Celery,不阻塞 API
- LLM 调用必须 timeout + 重试 + 熔断
- 用 ruff 严格模式避免低级错误

## 与 Java 的边界

**Python 不做的事:**
- ❌ 不连主业务数据库(只能读自己的 task_result)
- ❌ 不处理权限(权限由 Java 校验,Python 只接收已授权的请求)
- ❌ 不维护业务事务

**Python 做的事:**
- ✅ 调 LLM(读 Java 传入的 prompt + Schema)
- ✅ 写自己的 task_result / audit_log 表
- ✅ 触发外部 HTTP / Webhook
- ✅ 生成报表文件并上传到 MinIO
