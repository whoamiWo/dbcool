#!/usr/bin/env python3
"""R5：真实 PostgreSQL 集成测试门禁（Testcontainers）。

- 有 Docker：执行 `mvn -o test -Dtest=EnterpriseSuitePgIT,UnifiedSearchIndexRepositoryPgIT,ApprovalExternalBindingRepositoryPgIT`（标签 pg）。
- 无 Docker：显式打印 `SKIP:` 原因并退出 0 —— monthly-gate 会把 SKIP 单列，
  不计 PASS（见 scripts/monthly-gate.py）。

退出码：0 = PASS 或 SKIP；1 = 真实失败。
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def docker_available() -> bool:
    if not shutil.which("docker"):
        return False
    try:
        r = subprocess.run(["docker", "info"], capture_output=True, text=True, timeout=30)
        return r.returncode == 0
    except Exception:
        return False


def main() -> int:
    if not docker_available():
        print("GATE_SKIP: 1")
        print("SKIP: Docker 不可用，EnterpriseSuitePgIT（Testcontainers）未执行")
        return 0

    proc = subprocess.run(
        ["mvn", "-o", "test", "-Dtest=EnterpriseSuitePgIT,UnifiedSearchIndexRepositoryPgIT,ApprovalExternalBindingRepositoryPgIT"],
        cwd=ROOT / "backend-java",
        capture_output=True,
        text=True,
        timeout=1800,
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    print("\n".join(out.strip().splitlines()[-25:]))

    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", out)
    if m:
        total, failures, errors, skipped = map(int, m.groups())
        if total == skipped and total > 0:
            print(f"GATE_SKIP: {skipped}")
            print(f"SKIP: {skipped} 项 pg 测试被跳过（Testcontainers 无法启动）")
            return 0
        print(f"PG IT: tests={total} failures={failures} errors={errors} skipped={skipped}")
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
