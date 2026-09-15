# 演示兜底视频清单

> 这些视频**不入 git**(体积大),存云盘 + 本地双备份。

## 视频 1 — 30 秒核心流程
- **文件名**:`demo-30s-core-flow.mp4`
- **内容**:
  1. 登录(0-5s)
  2. 创建 collection "posts" + 加字段 title/text, status/select(5-15s)
  3. 创建一条记录并触发 on_create workflow(15-25s)
  4. 显示工作流实例 RUNNING → COMPLETED(25-30s)
- **用途**:主程序崩 / 网络断 / 数据库崩 时的兜底
- **位置**:OneDrive `/demo/fallback/demo-30s-core-flow.mp4`

## 视频 2 — 5 分钟完整 demo
- **文件名**:`demo-5min-full.mp4`
- **内容**:从空白数据库到 CRUD + Workflow + 多租户切换的完整流程
- **用途**:正式 demo 因故不能实时跑时的备用
- **位置**:OneDrive `/demo/fallback/demo-5min-full.mp4`

## 视频 3 — 错误恢复演示
- **文件名**:`demo-error-recovery.mp4`
- **内容**:故意制造死循环(workflow 自触发) → 看到 rate limit 阻断 → unfreeze → 恢复
- **用途**:展示风险防护能力
- **位置**:OneDrive `/demo/fallback/demo-error-recovery.mp4`

## 检查清单

- [ ] 视频在 OneDrive 链接可访问
- [ ] 视频下载到本地演示机器 `~/demo-backup-videos/`
- [ ] 视频在演示机器上用 VLC 播放过(无编码问题)
- [ ] 应急链接文档离线副本(打印)
