# 演示代码冻结流程(R13)

> 目标:**演示当天不因临时改动引入 bug;出问题时能 1 分钟回滚。**

## 演示前 1 周 — 准备

1. **代码冻结**:在 main 分支跑
   ```bash
   ./scripts/freeze-demo.sh v0.5.0-demo "首次正式演示"
   ```
   自动完成:
   - 跑 `mvn verify`(全绿才继续,失败则阻断冻结)
   - 创建 git tag `v0.5.0-demo`
   - 创建/更新 `release/demo` 分支(指针同 main)
   - 生成 `docs/RELEASE_NOTES.md`(最近 50 commits)
   - 推到远程:`git push origin v0.5.0-demo release/demo`

2. **兜底视频**:录 30 秒核心流程的兜底视频(创建 collection → 加字段 → 创建记录 → 触发工作流)
   - 存到云盘(OneDrive / Dropbox / 公司 NAS),**不入 git**(体积大)
   - 文档:`docs/DEMO_FALLBACK_VIDEO.md` 列出文件名 + 链接

3. **环境预演**:在演示机器上完整跑一遍 E2E,验证:
   - 后端启动 (`./mvnw spring-boot:run`)
   - 前端构建 (`pnpm build`) + 启动 (`pnpm preview`)
   - 数据库迁移 (Flyway V1-V16)
   - 登录 + CRUD + 工作流 三个核心流程

4. **现场模式**:
   - 关闭 IDE 自动保存,避免误改
   - 只读终端窗口(`tmux`,不能直接编辑文件)
   - 备份本地 working tree 到 `~/demo-backup-$(date +%s)`

## 演示当天 — 出问题时的应急

### A. 后端崩
```bash
./scripts/unfreeze-demo.sh v0.5.0-demo
# → 切到 tag, 后端重启即可
```

### B. 前端崩
切到 `release/demo` 分支对应的前端 dist:
```bash
cd frontend
git checkout v0.5.0-demo -- dist/  # 恢复前次构建产物
# 或重新构建
git checkout release/demo
pnpm install --frozen-lockfile
pnpm build
```

### C. 数据库崩
使用演示前导出的 dump:
```bash
pg_restore -d nocobase /path/to/demo-backup.dump
```

### D. 完全崩(无法恢复)
切换到兜底视频(`docs/DEMO_FALLBACK_VIDEO.md` 中链接),同时:
1. 立即 freeze 新版本:`./scripts/freeze-demo.sh v0.5.1-demo "演示兜底"`
2. 录屏现场错误信息
3. 现场观众看到的是 backup 视频 + 你"修复"的过程(透明)

## 演示结束 — 恢复 main

```bash
git checkout main
# 若 main 有临时修复:
git diff main..release/demo  # 查 demo 分支的修复
git cherry-pick <commit-sha>   # 把 demo 分支修复带回 main
git push origin main
```

## 检查清单(打印出来贴墙上)

- [ ] `./scripts/freeze-demo.sh v0.X.Y-demo "..."` 已跑且成功
- [ ] `git tag -l` 含 `v0.X.Y-demo`
- [ ] `git branch -r` 含 `origin/release/demo`
- [ ] `docs/RELEASE_NOTES.md` 已生成
- [ ] `docs/DEMO_FALLBACK_VIDEO.md` 链接有效,视频可播
- [ ] 数据库 dump 备份到云盘
- [ ] E2E 在演示机器上跑过
- [ ] 后端 + 前端 在演示机器上启动过
- [ ] 现场模式就绪(只读终端 + 工作树备份)

## 风险评估

| 风险 | 概率 | 缓解 |
|---|---|---|
| 主程序崩 | 中 | unfreeze 1 分钟内回滚 |
| 数据库崩 | 低 | pg_restore 5 分钟 |
| 网络断(API 失效) | 低 | 后端本地启动,演示离线 |
| 现场改代码引入 bug | **高** | 只读终端 + freeze 后禁止 push |
| 观众问到当前版本没有的功能 | 高 | 现场说"Week 43+ 计划中",不现场加功能 |
