# ADR-008: 插件系统采用 Java SPI + REST Hook

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: 全部

## 背景

低代码平台的可扩展性核心是插件系统:
- 第三方可开发新字段类型(如:地理坐标、签名)
- 第三方可开发新工作流节点(如:钉钉通知)
- 第三方可开发新视图类型(如:甘特图)
- 不重启主程序加载

## 决策

### Java 侧(后端)
- 基于 **Java SPI**(`META-INF/services`)
- 插件打成 jar,放在 `plugins/` 目录
- 启动时扫描 + 加载到 `PluginRegistry`
- 提供三类扩展点:
  - `FieldTypeProvider`(新字段类型)
  - `WorkflowNodeProvider`(新节点)
  - `ViewTypeProvider`(新视图)

### 前端侧
- 插件前端代码也打成 npm 包
- 后端 manifest 列出前端 bundle URL
- 前端启动时拉取所有插件 manifest,动态 import

### Python 侧
- 用 **entry_points**(setuptools)
- 类似 Java SPI 机制

## 插件契约(最小)

```yaml
# plugin.yaml
name: dingtalk-notify
version: 1.0.0
author: third-party
backend:
  type: java-spi
  class: com.example.dingtalk.DingTalkNodeProvider
frontend:
  entry: dist/plugin.js
permissions:
  - workflow.node.register
  - http.external.dingtalk.com
```

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| OSGi(Java) | 标准 | 重、复杂 | overkill |
| 自研 classloader | 灵活 | 实现复杂、易出错 | 用成熟 SPI |
| 微内核 + RPC | 强隔离 | 性能差、调试难 | 单进程足够 |

## 后果

### 正面
- 标准机制,无需自研基础设施
- 启动时一次性加载,运行时零开销
- 与现有 Java 生态兼容

### 负面
- 插件版本冲突需用 `maven-shade-plugin` 隔离
- 前端动态 import 需前端构建支持 module federation(后期)

### 缓解措施
- 制定插件命名规范,避免包冲突
- 插件必须自描述(`getManifest()`)
- 提供 `plugin-dev-kit` 脚手架
