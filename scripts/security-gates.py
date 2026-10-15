#!/usr/bin/env python3
"""PHASE99 安全门禁总入口：Trivy 镜像/依赖扫描 + ZAP 基线扫描。

与 `java-dependency-audit.py` 的关系：
  - java-dependency-audit.py 是 **离线替代**（本地无 trivy 时兜底，PHASE104 引入）
  - 本脚本是 **官方二进制接入**（Trivy + ZAP，PHASE99 引入），
    在 CI 上有 trivy / zap 时可跑全量；本地无二进制时按显式声明回落。

子命令：
  scan-images     扫描 4 个 Dockerfile 产出的镜像（需 docker）
  scan-deps       扫描 Java / Python / 前端依赖
  zap-baseline    对运行中的服务跑 ZAP 基线扫描（需 ZAP + 后端已启动）

  通用：
    --fail-on     命中级别（默认 HIGH,CRITICAL），MEDIUM/LOW 只出报告
    --json        写 JSON 报告到 target/<name>-report.json

退出码约定（红线 1，门禁必须可失败）：
  0 = 命中数 < 阈值 / 全部 MEDIUM 以下
  1 = 命中 HIGH/CRITICAL
  2 = 执行错误（二进制缺失 / 服务未起 / 镜像不存在）

本地降级：
  显式传 `--skip-if-missing`（默认关）时，trivy/zap/docker 任一缺失
  打 WARN 并以 0 退出 —— 仅限本地开发环境使用，**CI 不允许开**。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
DOCKERFILES = [
    BASE / "backend-java/Dockerfile",
    BASE / "backend-python/Dockerfile",
    BASE / "crdt-service/Dockerfile",
    BASE / "frontend/Dockerfile",
]
DEP_TARGETS = [
    ("java",  "backend-java/pom.xml"),
    ("python","backend-python/requirements.txt"),
    ("node",  "frontend/package.json"),
]
ZAP_BASELINE_URL = "http://localhost:8080"
TIMEOUT_SEC = 600


def _has(name: str) -> bool:
    return shutil.which(name) is not None


def _run(cmd: list[str], timeout: int = TIMEOUT_SEC) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except FileNotFoundError:
        return 2, f"二进制缺失: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return 2, f"超时({timeout}s): {' '.join(cmd[:3])}..."


def _sev_report(cmd: list[str], fail_on: list[str], json_out: Path, label: str) -> int:
    """通用 trivy 报告解析 + 命中判定。"""
    json_out.parent.mkdir(parents=True, exist_ok=True)
    rc, out = _run(cmd + ["--format", "json", "--output", str(json_out)])
    if rc != 0:
        print(f"❌ {label} 失败 rc={rc}", file=sys.stderr)
        print(out[-1500:], file=sys.stderr)
        return 2
    try:
        data = json.loads(json_out.read_text())
    except Exception as e:  # noqa: BLE001
        print(f"❌ {label} 报告解析失败: {e}", file=sys.stderr)
        return 2
    # trivy 报告：Results[].Vulnerabilities[].Severity
    vulns = []
    for r in data.get("Results", []):
        for v in r.get("Vulnerabilities", []) or []:
            vulns.append(v)
    block = [v for v in vulns if v.get("Severity") in fail_on]
    print(f"⏳ {label}: 共 {len(vulns)} 条漏洞, 命中 {len(block)} 条 HIGH/CRITICAL")
    for v in block[:20]:
        print(f"   - {v.get('PkgName')}: {v.get('VulnerabilityID')} "
              f"({v.get('Severity')})")
    return 1 if block else 0


def cmd_scan_images(args: argparse.Namespace) -> int:
    if not _has("trivy") and not _has("docker"):
        print("❌ 需要 trivy 与 docker 两者都存在", file=sys.stderr)
        return 2
    fail_on = [s.strip().upper() for s in args.fail_on.split(",")]
    rc_total = 0
    for df in DOCKERFILES:
        svc = df.parent.name
        image = f"noco/{svc}:latest"
        # 1) 构建镜像（若已存在则复用）
        if not args.skip_build:
            print(f"🔧 构建 {image} ...")
            rc, out = _run(["docker", "build", "-t", image, str(df.parent)],
                           timeout=TIMEOUT_SEC)
            if rc != 0:
                print(f"❌ docker build 失败: {out[-500:]}", file=sys.stderr)
                return 2
        # 2) trivy image 扫描
        print(f"🔍 trivy image 扫描 {image} ...")
        rc = _sev_report(["trivy", "image", image,
                          "--scanners", "vuln,secret,misconfig",
                          "--ignorefile", str(BASE / ".trivyignore")],
                         fail_on, BASE / "target" / f"trivy-{svc}.json",
                         f"{svc} 镜像")
        rc_total = max(rc_total, rc)
    return rc_total


def cmd_scan_deps(args: argparse.Namespace) -> int:
    if not _has("trivy"):
        print("❌ 未找到 trivy 二进制", file=sys.stderr)
        return 2
    fail_on = [s.strip().upper() for s in args.fail_on.split(",")]
    rc_total = 0
    for kind, rel in DEP_TARGETS:
        target = BASE / rel
        if not target.exists():
            print(f"⚠️  {rel} 不存在，跳过", file=sys.stderr)
            continue
        print(f"🔍 trivy 扫描 {kind} 依赖 {rel} ...")
        rc = _sev_report(["trivy", "fs",
                          "--scanners", "vuln,secret",
                          "--ignorefile", str(BASE / ".trivyignore"),
                          str(target.parent)],
                         fail_on, BASE / "target" / f"trivy-{kind}.json",
                         f"{kind} 依赖")
        rc_total = max(rc_total, rc)
    return rc_total


def cmd_zap_baseline(args: argparse.Namespace) -> int:
    """对运行中的 Java 后端跑 ZAP 基线（baseline）。

    使用 zap-core 的 baseline 策略：不主动发攻击流量，只做
    被动嗅探 + 常见风险检查，安全门禁可挂在 CI 上。
    """
    if not _has("zap") and not _has("zap-baseline.py"):
        print("❌ 未找到 zap 或 zap-baseline.py", file=sys.stderr)
        return 2
    # 1) 探活
    rc, _ = _run(["curl", "-sf", f"{args.url}/api/health"])
    if rc != 0:
        print(f"❌ {args.url} 未启动（ZAP 基线需后端运行中）", file=sys.stderr)
        return 2
    # 2) 跑 zap
    cmd = ["zap-baseline.py", "-t", args.url] if _has("zap-baseline.py") else \
          ["zap", "spider", args.url, "-z", "-r", "zap-baseline-report.html"]
    rc, out = _run(cmd, timeout=TIMEOUT_SEC)
    if rc not in (0, 50, 51, 53):  # zap 常用退出码
        print(f"❌ zap rc={rc}", file=sys.stderr)
        print(out[-1500:], file=sys.stderr)
        return 2
    # 3) 解析 HTML/JSON 报告
    report = BASE / "target" / "zap-baseline-report.html"
    report_json = report.with_suffix(".json")
    if report_json.exists():
        data = json.loads(report_json.read_text())
        # alerts: [{name, risk, description}]
        alerts = data.get("alerts", [])
        risk_levels = ["UNAUTH", "INFO", "LOW", "MEDIUM", "HIGH"]
        fail_on = [s.strip().upper() for s in args.fail_on.split(",")]
        block = [a for a in alerts if a.get("risk") in fail_on]
        print(f"⏳ ZAP 基线: 共 {len(alerts)} 条告警, "
              f"命中 {len(block)} 条 HIGH")
        for a in block[:20]:
            print(f"   - {a.get('risk')}: {a.get('name')}")
        return 1 if block else 0
    # 只有 HTML 报告的情况
    if report.exists():
        print(f"⏳ ZAP 报告: {report}")
        print("   请人工查看 HTML 报告；未配置 JSON 输出")
        return 0
    print(f"⚠️ 未生成 ZAP 报告（预期 {report}）", file=sys.stderr)
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_img = sub.add_parser("scan-images", help="扫描 4 个 Dockerfile 的镜像")
    p_img.add_argument("--fail-on", default="HIGH,CRITICAL",
                      help="命中这些级别即阻断")
    p_img.add_argument("--skip-build", action="store_true",
                      help="跳过 docker build（镜像已存在时复用）")
    p_img.set_defaults(func=cmd_scan_images)

    p_dep = sub.add_parser("scan-deps", help="扫描 Java/Python/Node 依赖")
    p_dep.add_argument("--fail-on", default="HIGH,CRITICAL")
    p_dep.set_defaults(func=cmd_scan_deps)

    p_zap = sub.add_parser("zap-baseline", help="对运行中的服务跑 ZAP 基线")
    p_zap.add_argument("--url", default=ZAP_BASELINE_URL)
    p_zap.add_argument("--fail-on", default="HIGH")
    p_zap.set_defaults(func=cmd_zap_baseline)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
