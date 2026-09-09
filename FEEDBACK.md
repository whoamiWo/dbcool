# 给 Prime Agent 的反馈通道

> Prime 各栈 daemon 在每次启动新任务时,**必须先完整阅读本文件**
> Cline 在审计 Prime 改动后,把反馈结构化追加到本文件

---

## 📐 反馈格式

```markdown
## [YYYY-MM-DD HH:MM] 来源: Cline · <任务编号 T-XXX>

- **类型**: ACCEPT / REVISE / REVERT / QUESTION
- **目标栈**: java / python / js / cross
- **涉及文件**: (列表)
- **问题描述**: (具体,避免空泛)
- **改进建议**: (具体,避免"代码风格不一致"这种主观判断)
- **关键引用**: (文件:行号 或 commit hash)
- **关联标准**: OPTIMIZATION_PLAN.md 中对应验收标准
```

---

## 🚨 高优先级反馈(Prime 下一轮必须先处理)

<!-- Cline 在此处 append 高严重度反馈 -->

---

## 📋 普通反馈队列

<!-- Cline 在此处 append 一般反馈 -->

---

## ✅ 已处理反馈

<!-- Prime 处理后,移到此处,并标注处理 commit hash -->
