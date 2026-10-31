#!/usr/bin/env python3
"""
Java 依赖漏洞扫描（OWASP Dependency-Check / Trivy 的离线替代方案）。

背景：PHASE104 审计登记"Trivy/OWASP 未集成（需额外二进制）"，但本环境
离线且无法下载。本脚本用**已有工具链**（mvn dependency:tree + 本地
已知漏洞库）实现同等能力：

  1. 生成 SBOM：`mvn -o dependency:tree` 解析 group:artifact:version
  2. 比对漏洞库：`config/known-vulnerabilities.json`（人工维护的 CVE 清单）
  3. 输出报告：命中数、严重级别、失败判定

用法：
    python3 scripts/java-dependency-audit.py [--json report.json]

退出码：0 = 无高危；1 = 有 HIGH/CRITICAL；2 = 执行错误

红线：禁止恒真断言 —— 漏洞库为空或解析失败必须显式报错，不得默认 PASS。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
POM_DIR = BASE / "backend-java"
VULN_DB = BASE / "config" / "known-vulnerabilities.json"

# 解析 mvn dependency:tree 输出：group:artifact:jar:version:scope
# 采用两步提取：先 group:artifact，再 jar:version:scope
# 关键：跳过树形前缀（+- | 等），直接定位坐标格式
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")
# 匹配 "group:artifact:jar" 且前面是任意非字母数字字符（跳过树前缀）
GROUP_ARTIFACT_RE = re.compile(r"[^a-zA-Z0-9](?:[\w.\-]+):([\w.\-]+):jar")
JAR_VERSION_SCOPE_RE = re.compile(r":jar:([\w.\-]+):(\w+)$")

# 严重级别排序（用于判定阻断）
SEVERITY_ORDER = {"CRITICAL": 4, "HIGH": 3, "MEDIUM": 2, "LOW": 1, "UNKNOWN": 0}
BLOCK_LEVELS = {"CRITICAL", "HIGH"}

# 官方工具调用超时（秒）
OFFICIAL_TIMEOUT = 600


def which_binary(name: str) -> bool:
    """探测系统 PATH 中是否存在指定二进制。"""
    import shutil
    return shutil.which(name) is not None


def detect_backend(backend: str) -> str:
    """PHASE106 T1: 后端选择。

    - auto: 依次探测 trivy / dependency-check，命中即用对应官方后端；
            均未命中则回落 offline 并打印 WARN（不静默）。
    - trivy / owasp: 显式指定官方后端，且要求二进制存在（缺失由调用方报错）。
    - offline: 显式强制离线比对。
    返回实际使用的后端名。
    """
    if backend != "auto":
        return backend
    if which_binary("trivy"):
        print("⚙️  [auto] 检测到 trivy，使用官方 Trivy 后端")
        return "trivy"
    if which_binary("dependency-check") or which_binary("dependency-check.sh"):
        print("⚙️  [auto] 检测到 OWASP dependency-check，使用官方 OWASP 后端")
        return "owasp"
    print("⚠️  [auto] 未检测到官方二进制 (trivy / dependency-check)，"
          "回落 offline 离线比对模式", file=sys.stderr)
    return "offline"


def run_official_backend(backend: str) -> int:
    """PHASE106 T1: 调用官方工具产真实 CVE 报告，映射 exit 码。

    - trivy:  trivy fs --format json --output <out> <pom>
    - owasp:  dependency-check --scan <pom> --format JSON --out <dir>
    官方工具调用失败(rc!=0 / 超时 / 二进制缺失) → ERROR + exit 2；
    解析后按命中 CRITICAL/HIGH 决定 exit 0/1。
    """
    if backend == "trivy":
        return _run_trivy()
    elif backend == "owasp":
        return _run_owasp()
    else:
        print(f"❌ 未知官方后端: {backend}", file=sys.stderr)
        return 2


def _run_trivy() -> int:
    out = POM_DIR / "target" / "trivy-report.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["trivy", "fs", "--format", "json", "--output", str(out),
           "--skip-dirs", "target", str(POM_DIR)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              timeout=OFFICIAL_TIMEOUT)
    except (subprocess.TimeoutExpired, FileNotFoundError) as e:
        print(f"❌ trivy 调用失败: {e}", file=sys.stderr)
        return 2
    if proc.returncode != 0:
        print(f"❌ trivy 扫描失败 (rc={proc.returncode})", file=sys.stderr)
        print((proc.stderr or proc.stdout)[-2000:], file=sys.stderr)
        return 2
    return _report_from_official(out, "trivy")


def _run_owasp() -> int:
    out_dir = POM_DIR / "target" / "owasp-output"
    out_dir.mkdir(parents=True, exist_ok=True)
    bin_name = "dependency-check"
    if not which_binary(bin_name) and which_binary("dependency-check.sh"):
        bin_name = "dependency-check.sh"
    cmd = [bin_name, "--scan", str(POM_DIR / "pom.xml"),
           "--format", "JSON", "--out", str(out_dir)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              timeout=OFFICIAL_TIMEOUT)
    except (subprocess.TimeoutExpired, FileNotFoundError) as e:
        print(f"❌ OWASP dependency-check 调用失败: {e}", file=sys.stderr)
        return 2
    if proc.returncode != 0:
        print(f"❌ OWASP 扫描失败 (rc={proc.returncode})", file=sys.stderr)
        print((proc.stderr or proc.stdout)[-2000:], file=sys.stderr)
        return 2
    # OWASP 输出名固定为 dependency-check-report.json
    report_file = out_dir / "dependency-check-report.json"
    if not report_file.exists():
        print(f"❌ OWASP 报告缺失: {report_file}", file=sys.stderr)
        return 2
    return _report_from_official(report_file, "owasp")


def _report_from_official(report_file: Path, backend: str) -> int:
    """解析官方工具 JSON，按命中 CRITICAL/HIGH 决定 exit 码。

    trivy: 结果结构 {"Results":[{"Vulnerabilities":[{"VulnerabilityID","Severity"}]}]}
    owasp: 结果结构 {"vulnerabilities":[{"cve","severity"}]}
    """
    try:
        data = json.loads(report_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as e:
        print(f"❌ 解析 {backend} 报告失败: {e}", file=sys.stderr)
        return 2

    findings: list[tuple[str, str]] = []  # (id, severity)
    if backend == "trivy":
        for res in data.get("Results", []):
            for vuln in res.get("Vulnerabilities", []):
                findings.append((vuln.get("VulnerabilityID", "UNKNOWN"),
                                 vuln.get("Severity", "UNKNOWN").upper()))
    elif backend == "owasp":
        for vuln in data.get("vulnerabilities", []):
            findings.append((vuln.get("cve", "UNKNOWN"),
                            (vuln.get("severity") or "UNKNOWN").upper()))

    blocking = [f for f in findings if f[1] in BLOCK_LEVELS]
    print(f"[{backend}] 命中 {len(findings)} 条漏洞，阻断级 {len(blocking)} 条")
    for cid, sev in sorted(blocking, key=lambda f: -SEVERITY_ORDER.get(f[1], 0)):
        print(f"  [{sev:8}] {cid}")
    print("=" * 62)
    return 1 if blocking else 0


def run_dependency_tree() -> list[dict]:
    """离线跑 mvn dependency:tree 并解析依赖清单。

    注意：不能用 -q，否则 INFO 级别的依赖树被吞掉 → 解析到 0 条依赖，
    扫描会退化成"恒真 PASS"（红线 1：禁止恒真断言）。
    """
    # v2.1.3：先离线（本地有 ~/.m2 缓存，快且稳定）；CI 等环境无完整缓存时
    # offline 会失败 → 自动回退在线模式重试一次（依赖已在 verify 阶段下载过）。
    commands = [["mvn", "-o", "dependency:tree"], ["mvn", "-B", "dependency:tree"]]
    proc = None
    for cmd in commands:
        try:
            proc = subprocess.run(cmd, cwd=POM_DIR, capture_output=True, text=True, timeout=600)
        except (subprocess.TimeoutExpired, FileNotFoundError) as e:
            print(f"❌ 无法执行 {cmd[0]} dependency:tree: {e}", file=sys.stderr)
            sys.exit(2)
        if proc.returncode == 0:
            break
        print(f"⚠️ {cmd[0]} -o dependency:tree 失败 (rc={proc.returncode})，尝试在线重试..." if cmd == commands[0]
              else f"❌ mvn dependency:tree 失败 (rc={proc.returncode})", file=sys.stderr)
        if cmd == commands[0]:
            print(proc.stderr[-2000:], file=sys.stderr)
    if proc is None or proc.returncode != 0:
        sys.exit(2)

    deps: dict[tuple[str, str], dict] = {}
    for raw_line in proc.stdout.splitlines():
        # mvn 输出常带 \r\n，先 strip 再 strip ANSI
        line = ANSI_RE.sub("", raw_line).strip()
        m1 = GROUP_ARTIFACT_RE.search(line)
        m2 = JAR_VERSION_SCOPE_RE.search(line)
        if not (m1 and m2):
            continue
        # group 是坐标中第一个冒号前的部分（需 strip 掉树前缀残留空格）
        coord = line[m1.start():]
        group = coord.split(":")[0].strip()
        artifact = m1.group(1)
        version, scope = m2.group(1), m2.group(2)
        key = (group, artifact)
        # 同一 artifact 可能多版本，保留 compile 优先
        if key not in deps or scope == "compile":
            deps[key] = {
                "group": group, "artifact": artifact,
                "version": version, "scope": scope,
            }

    # 零依赖 = 解析失败，必须显式报错，不得当作"无漏洞"通过
    if not deps:
        print("❌ 未解析到任何依赖（解析失败），拒绝输出 PASS", file=sys.stderr)
        sys.exit(2)

    return list(deps.values())


def load_vuln_db() -> list[dict]:
    """加载已知漏洞库。空库/缺失必须显式失败（禁止恒真 PASS）。"""
    if not VULN_DB.exists():
        print(f"❌ 漏洞库不存在: {VULN_DB}", file=sys.stderr)
        print("   请创建该文件（可为空数组 []，但不可缺失）。", file=sys.stderr)
        sys.exit(2)
    try:
        data = json.loads(VULN_DB.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"❌ 漏洞库 JSON 解析失败: {e}", file=sys.stderr)
        sys.exit(2)
    if not isinstance(data, list):
        print(f"❌ 漏洞库必须是数组，实际为 {type(data).__name__}", file=sys.stderr)
        sys.exit(2)
    if len(data) == 0:
        print("❌ 漏洞库为空（0 条），门禁不得默认 PASS（禁止恒真）", file=sys.stderr)
        sys.exit(2)
    return data


def version_in_range(version: str, affected: str) -> bool:
    """简化版本区间判断：支持 '>=1.0,<2.0'、'<2.0'、'1.2.3' 精确匹配。"""
    version = version.strip()
    for part in affected.split(","):
        part = part.strip()
        if not part:
            continue
        m = re.match(r"(>=|<=|>|<|=)?\s*(.+)", part)
        if not m:
            continue
        op, target = m.group(1) or "=", m.group(2).strip()
        if op == "=":
            if version == target:
                return True
        elif op == ">=":
            if version_tuple(version) >= version_tuple(target):
                return True
        elif op == ">":
            if version_tuple(version) > version_tuple(target):
                return True
        elif op == "<=":
            if version_tuple(version) <= version_tuple(target):
                return True
        elif op == "<":
            if version_tuple(version) < version_tuple(target):
                return True
    return False


def version_tuple(v: str) -> tuple:
    """把 '1.2.3' 转 (1,2,3)；非数字段按 0 处理，保证可比较。"""
    parts = re.split(r"[.\-]", v.strip())
    out = []
    for p in parts:
        out.append(int(p) if p.isdigit() else 0)
    return tuple(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", help="输出 JSON 报告路径")
    ap.add_argument("--sbom", help="额外输出 SBOM 清单路径")
    ap.add_argument("--backend", choices=["auto", "trivy", "owasp", "offline"],
                    default="auto",
                    help="漏洞扫描后端: auto(默认，自动探测官方工具，回落离线)/"
                         "trivy/owasp(强制官方工具，缺失则报错)/offline(强制离线比对)")
    args = ap.parse_args()

    # PHASE106 T1: 双轨后端 —— 显式指定官方后端时，缺失即报错，不静默回落
    resolved = detect_backend(args.backend)
    if resolved in ("trivy", "owasp"):
        # 显式指定官方后端必须保证二进制存在，否则 exit 2（红线：不得静默）
        required = "trivy" if resolved == "trivy" else "dependency-check"
        if not which_binary(required) and not (resolved == "owasp" and which_binary("dependency-check.sh")):
            print(f"❌ 指定 --backend {resolved} 但未找到 '{required}' 二进制，"
                  f"请安装或使用 --backend auto/offline", file=sys.stderr)
            return 2
        print("=" * 62)
        print(f"Java 依赖漏洞扫描（官方 {resolved} 后端）")
        print("=" * 62)
        rc = run_official_backend(resolved)
        if args.json:
            out = Path(args.json)
            if not out.is_absolute():
                out = POM_DIR / "target" / out.name
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(
                {"backend": resolved, "exit_code": rc},
                ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"JSON 报告: {out}")
        return rc

    print("=" * 62)
    print("Java 依赖漏洞扫描（离线替代方案）")
    print("=" * 62)

    deps = run_dependency_tree()
    print(f"依赖总数: {len(deps)}")

    vulns = load_vuln_db()
    print(f"漏洞库条目: {len(vulns)}")

    findings: list[dict] = []
    for dep in deps:
        for v in vulns:
            if v.get("group") != dep["group"] or v.get("artifact") != dep["artifact"]:
                continue
            affected = v.get("affected", "")
            if not version_in_range(dep["version"], affected):
                continue
            findings.append({
                **dep,
                "cve": v.get("cve", "UNKNOWN"),
                "severity": v.get("severity", "UNKNOWN"),
                "description": v.get("description", ""),
                "fixed_in": v.get("fixed_in", ""),
            })

    # 按严重级别排序输出
    findings.sort(key=lambda f: -SEVERITY_ORDER.get(f["severity"], 0))

    print()
    if not findings:
        print("✅ 未命中已知漏洞")
    else:
        print(f"⚠️  命中 {len(findings)} 条已知漏洞：")
        for f in findings:
            print(f"  [{f['severity']:8}] {f['group']}:{f['artifact']}:{f['version']} "
                  f"({f['cve']}) 修复版本: {f['fixed_in'] or '未知'}")
            print(f"             {f['description'][:100]}")

    blocking = [f for f in findings if f["severity"] in BLOCK_LEVELS]
    print()
    print(f"阻断级（CRITICAL/HIGH）: {len(blocking)} 条")

    report = {
        "backend": "offline",
        "total_dependencies": len(deps),
        "vuln_db_entries": len(vulns),
        "findings": findings,
        "blocking_count": len(blocking),
        "passed": len(blocking) == 0,
    }

    if args.json:
        out = Path(args.json)
        if not out.is_absolute():
            # 相对路径统一落到 backend-java/target/（与 CI 的 artifact 路径一致）
            out = POM_DIR / "target" / out.name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"JSON 报告: {out}")

    if args.sbom:
        out = Path(args.sbom)
        if not out.is_absolute():
            out = POM_DIR / "target" / out.name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(deps, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"SBOM 清单: {out}")

    print("=" * 62)
    if blocking:
        print("❌ 存在高危漏洞，门禁应阻断")
        return 1
    print("✅ 无高危漏洞")
    return 0


if __name__ == "__main__":
    sys.exit(main())
