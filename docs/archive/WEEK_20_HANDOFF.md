# Week 20 Handoff(抬红线 + GitHub Actions CI)

**周期**:Week 20  
**Sprint 目标**:1) 加测试抬 Jacoco 红线;2) 接 GitHub Actions 让红线真正在 CI 生效。  
**状态**:✅ 完成(2 commits + 1 docs)

---

## 🎯 两个故事

### ✅ 故事 A:抬 Jacoco 红线

**加新测试覆盖关键模块**:

| 测试类 | tests | 覆盖模块 | 提升效果 |
|--------|------|---------|---------|
| `RowAclServiceTest` | **18 PASS** | RowAclService 全部 op + principal | acl 0% → **48%** |
| `CollectionServiceMatchFilterTest` | **24 PASS** | CollectionService.matchFilter / toDouble | meta 8% → **10%** |
| `CollectionService.matchFilter` 改 package-private | — | (Week 19 教训) | 测试直接调 → JaCoCo 计入 |

**红线提升**:

| 范围 | Week 19 红线 | Week 20 红线 | 提升幅度 |
|------|-------------|-------------|---------|
| **bundle** | 5% | **8%** | +3% |
| `com.nocobase.auth` | 20% | **25%** | +5% |
| `com.nocobase.meta` | 5% | **10%** | +5% |
| `com.nocobase.acl` | (无) | **45%(新)** | +45% |

**总成绩**:70 tests → **112 tests PASS**(新 42)

### ✅ 故事 B:GitHub Actions CI

**`.github/workflows/backend-ci.yml`**:

```yaml
name: Backend CI
on:
  push: branches: [main] paths: [backend-java/**, ...]
  pull_request: branches: [main]
concurrency: backend-ci-${ref} cancel-in-progress: true

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - checkout
      - setup-java@v4 (JDK 21 + Maven cache)
      - mvn -B verify (Tests + JaCoCo 红线一起跑)
      - upload-artifact: target/site/jacoco/ (30 天保留)
```

**特点**:
- ✅ PR / push 到 main 触发
- ✅ 取消进行中的旧 run(节省 CI 时间)
- ✅ path filter:只 backend-java 变更才触发
- ✅ Maven cache(加快)
- ✅ coverage report 作 artifact
- ⏸️ **不加 service containers**(集成测试未引入)

**当前限制**:GitHub SSH 不可达,workflow 文件本地写,push 后才生效。

---

## 📁 本会话文件

**修改:**
- `backend-java/src/main/java/com/nocobase/meta/CollectionService.java`(`matchFilter` / `toDouble` 改 package-private)
- `backend-java/pom.xml`(红线提升 + acl 新增规则)
- `README.md`(加 CI / coverage badge)

**新增:**
- `backend-java/src/test/java/com/nocobase/acl/RowAclServiceTest.java`(18 tests)
- `backend-java/src/test/java/com/nocobase/meta/CollectionServiceMatchFilterTest.java`(24 tests)
- `.github/workflows/backend-ci.yml`(完整 CI workflow)
- `WEEK_20_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 20 段落

---

## 📊 覆盖率全景

| 包 | Week 19 | Week 20 | 备注 |
|----|--------|--------|------|
| `auth` | 26% | **25%** | 持平(本次未新增 auth 测试) |
| `meta` | 8% | **10%** | MatchFilter 24 tests 贡献 |
| `acl` | 0% | **48%** | RowAclService 18 tests 巨幅提升 |
| `health` | 100% | 100% | 不变 |

**红线策略**:每周 +5%,直到行业标准 70%。

---

## 🚧 Week 21+ 候选

- **A**: 加更多测试继续抬红线(workflow / view / audit 包) — 半天
- **B**: Testcontainers PG 集成测试 — 1 周
- **C**: Codecov / SonarCloud 集成(动态 coverage badge)— 半天
- **D**: GitHub SSH 修复 — push workflow 真正生效
- **E**: 前端单测(vitest 已设,补测试覆盖率)— 1 周

---

## 🎬 下一步建议

| 候选 | 描述 | 估时 |
|------|------|------|
| A | 加更多测试抬红线到 10%/30%/15%/55% | 半天 |
| B | Testcontainers 集成测试 | 1 周 |
| C | Codecov badge(动态) | 半天 |

**推荐**:候选 **A**(半天)— 抬红线收益最大,且为下次 CI 跑做铺垫。

---

**Week 20 收官。红线从 5%/20%/5% 抬到 8%/25%/10%/45%(新 acl),CI workflow 就绪,等 push 生效。**