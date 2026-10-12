#!/usr/bin/env python3
"""
PHASE96 T8: "禁止静默成功"常态化防线。

扫描全仓假实现/静默成功特征，命中即 FAIL；确有登记的未实现项走显式白名单
（白名单条目必须带"谁、为什么不实现、什么时候做"，否则视为未登记）。

用法：
    python3 scripts/no-fake-impl-verify.py

设计原则：
- 只扫主源码（backend-java/src/main），不扫测试（测试里断言假成功是被禁止的，
  但那属于"测试要改写"，由各批任务书单独约束，不该让本脚本对存量测试开火）
- 白名单条目按 `文件:模式` 匹配，且必须带 reason 与 owner 与 until
- 退出码：0 = 无未登记命中；1 = 有未登记命中
"""
import os
import re
import sys
from pathlib import Path

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCAN_ROOTS = [
    "backend-java/src/main",
    "backend-python/src",
]

# ------------------------------------------------------------
# 特征模式（按严重度分组）
# ------------------------------------------------------------
# P0: 明确的假成功/占位承诺
P0_PATTERNS = [
    (r'mock-sent', '邮件假成功（未配置 SMTP 却返回成功）'),
    (r'logged-send', '邮件假成功（配了 SMTP 未开 real_send 却返回成功）'),
    (r'待后续迭代', '对用户暴露的占位承诺'),
    (r'"LDAP_SYNCED"', 'LDAP 占位口令（可从源码读到的已知口令）'),
]

# P1: 静默吞异常 / 无操作分支
P1_PATTERNS = [
    (r'catch\s*\(\s*\w+\s+\w+\s*\)\s*\{\s*\}', '空 catch 块吞异常'),
    (r'catch\s*\(\s*Exception\s+\w+\s*\)\s*\{\s*//', 'catch 后仅注释（无日志无抛出）'),
]

# 已知的合法"显式失败"表述（出现即说明是显式失败，不算假成功）
EXPLICIT_FAILURE_MARKERS = [
    'SendResult.error',
    'ResponseStatusException',
    'not_implemented',
    'UNIMPLEMENTED',
]

# ------------------------------------------------------------
# 显式白名单：确有登记的未实现项
# 每条必须带 owner / reason / until，缺一即视为未登记
# ------------------------------------------------------------
WHITELIST = [
    {
        'file': 'backend-java/src/main/java/com/nocobase/ldap/LdapTemplateConfig.java',
        'pattern': r'ldap://localhost:389',
        'owner': 'platform',
        'reason': 'LDAP 未启用时的显式失败占位（注释明写"不伪造用户数据"），'
                  '连接失败会抛明确异常而非假成功，属合规设计',
        'until': 'LDAP 正式启用时（PHASE99 租户级凭证）',
    },
    {
        # AuthController 的 LDAP_PLACEHOLDER 是"拒绝登录"的防线常量，不是占位口令本身
        'file': 'backend-java/src/main/java/com/nocobase/auth/AuthController.java',
        'pattern': r'LDAP_PLACEHOLDER = "LDAP_SYNCED"',
        'owner': 'security',
        'reason': 'PHASE96 T6 防线常量：命中该值的账号一律禁止本地登录（返回 403），'
                  '是为拦住 LdapSyncService 历史写死的已知口令，属显式失败设计',
        'until': '存量 LDAP_SYNCED 账号全部清理后（当前库内 0 条）',
    },
    # PHASE96 B4a: CollectionController 的空 catch 已修复为记 WARN 日志，
    # 该白名单条目随之删除 —— 修代码优先于扩白名单。
    {
        'file': 'backend-java/src/main/java/com/nocobase/workflow',
        'pattern': r'catch\s*\(\s*Exception\s+ignored\s*\)\s*\{\s*\}',
        'owner': 'workflow',
        'reason': 'workflow 节点参数解析容错（UUID.fromString），失败走默认分支',
        'until': 'PHASE98 错误态批次：节点入参统一收敛为显式 400',
    },
    {
        'file': 'backend-java/src/main/java/com/nocobase/im/SlashCommandInitializer.java',
        'pattern': r'catch\s*\(\s*NumberFormatException\s+ignored\s*\)\s*\{\s*\}',
        'owner': 'im',
        'reason': '/remind 的分钟数解析容错，失败回落默认 10 分钟，属用户输入容错',
        'until': 'PHASE98 错误态批次：斜杠命令入参统一加 400 校验',
    },
]

SKIP_DIRS = {'target', 'build', '__pycache__', 'node_modules', '.git', 'dist', 'venv'}
SKIP_EXT = {'.java', '.py'}


def iter_source_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            p = Path(dirpath) / fn
            if p.suffix in SKIP_EXT:
                yield p


def in_whitelist(rel_path: str, line: str) -> bool:
    """line 为命中行的完整文本，entry['pattern'] 是用于匹配该行的正则。"""
    for entry in WHITELIST:
        if entry['file'] in rel_path and re.search(entry['pattern'], line):
            # 白名单条目必须完整，缺 owner/reason/until 视为未登记
            if all(entry.get(k) for k in ('owner', 'reason', 'until')):
                return True
    return False


def main():
    print("=" * 70)
    print("PHASE96 T8: 禁止静默成功 —— 全仓假实现扫描")
    print("=" * 70)

    hits = []
    for root_rel in SCAN_ROOTS:
        root = Path(BASE_DIR) / root_rel
        if not root.exists():
            continue
        for path in iter_source_files(root):
            rel = str(path.relative_to(BASE_DIR))
            try:
                lines = path.read_text(encoding='utf-8').splitlines()
            except (UnicodeDecodeError, OSError):
                continue
            for lineno, line in enumerate(lines, 1):
                stripped = line.strip()
                # 纯注释行不扫（注释里讨论问题不算实现）
                if stripped.startswith(('//', '#', '*', '/*')):
                    continue
                for group, patterns in (('P0', P0_PATTERNS), ('P1', P1_PATTERNS)):
                    for pat, desc in patterns:
                        if re.search(pat, line):
                            if in_whitelist(rel, line):
                                continue
                            hits.append((group, rel, lineno, desc, line.strip()[:100]))

    # 去重（同文件同行同模式只报一次）
    seen = set()
    unique = []
    for h in hits:
        key = (h[1], h[2], h[3])
        if key not in seen:
            seen.add(key)
            unique.append(h)

    p0 = [h for h in unique if h[0] == 'P0']
    p1 = [h for h in unique if h[0] == 'P1']

    print(f"\n白名单登记 {len(WHITELIST)} 条（均带 owner/reason/until）")
    print(f"未登记命中：P0 {len(p0)} 条，P1 {len(p1)} 条\n")

    for group, rel, lineno, desc, line in unique:
        mark = "❌" if group == "P0" else "⚠️ "
        print(f"  {mark} [{group}] {rel}:{lineno} — {desc}")
        print(f"        {line}")

    if unique:
        print("\n" + "=" * 70)
        print("FAIL：存在未登记的假实现/静默成功特征。")
        print("处理方式二选一：")
        print("  1. 真实现；")
        print("  2. 显式失败（返回 error / 抛异常 / 标记 not_implemented）；")
        print("  3. 确需保留的，加进本脚本 WHITELIST 并补齐 owner/reason/until。")
        print("=" * 70)
        sys.exit(1)

    print("PASS：无未登记的假实现/静默成功特征。")
    sys.exit(0)


if __name__ == "__main__":
    main()
