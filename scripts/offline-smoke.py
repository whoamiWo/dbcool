#!/usr/bin/env python3
"""Credential-free HTTP readiness smoke checks for an offline deployment."""
from __future__ import annotations

import argparse
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ValueError(f"无法读取环境文件: {path.name}") from exc
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        key, separator, value = line.partition("=")
        if not separator:
            continue
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            quote = value[0]
            value = value[1:-1]
            if quote == '"':
                value = value.replace(r"\n", "\n").replace(r"\r", "\r").replace(r"\t", "\t")
                value = value.replace(r'\"', '"').replace(r"\\", "\\")
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        values[key] = value
    return values


def setting(name: str, values: dict[str, str], default: str) -> str:
    return values.get(name) or os.environ.get(name) or default


LOOPBACK_OR_WILDCARD = {"", "127.0.0.1", "localhost", "::1", "0.0.0.0", "::"}


def frontend_base(values: dict[str, str]) -> str:
    base = setting("SMOKE_BASE_URL", values, "").strip()
    if not base:
        host = setting("PUBLIC_HOST", values, "").strip()
        if host:
            port = setting("FRONTEND_TLS_PORT", values, "443").strip()
            base = f"https://{host}"
            if port and port != "443":
                base += f":{port}"
        else:
            bind = setting("FRONTEND_BIND_ADDR", values, "").strip()
            if re.search(r"[\s/]", bind) or "://" in bind:
                raise ValueError("FRONTEND_BIND_ADDR 无效")
            target = "127.0.0.1" if bind.casefold() in LOOPBACK_OR_WILDCARD else bind
            port = setting("FRONTEND_PORT", values, "80").strip()
            base = f"http://{target}:{port}"
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("SMOKE_BASE_URL 或 PUBLIC_HOST 无效")
    if parsed.query or parsed.fragment:
        raise ValueError("SMOKE_BASE_URL 不得包含 query 或 fragment")
    return base.rstrip("/")


def ssl_context(values: dict[str, str]) -> ssl.SSLContext:
    bundle = setting("SMOKE_CA_BUNDLE", values, "").strip()
    if not bundle:
        packaged = Path(__file__).resolve().parent / "certs" / "fullchain.pem"
        if packaged.is_file():
            bundle = str(packaged)
    if bundle:
        try:
            context = ssl.create_default_context(cafile=bundle)
        except (OSError, ssl.SSLError) as exc:
            raise ValueError(f"SMOKE_CA_BUNDLE 无法加载: {exc}") from exc
    else:
        context = ssl.create_default_context()
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED
    return context


def probe(url: str, label: str, quiet: bool, context: ssl.SSLContext) -> bool:
    request = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=5, context=context) as response:
            status = response.status
    except urllib.error.HTTPError as exc:
        status = exc.code
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        status = 0
    ok = status == 200
    if not quiet or not ok:
        result = f"HTTP {status}" if status else "连接失败或超时"
        print(f"{'OK' if ok else 'FAIL'} {label}: {result}", file=sys.stderr if not ok else sys.stdout)
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--frontend-only", action="store_true", help="只探测前端网关的存活与就绪端点")
    parser.add_argument("--quiet", action="store_true", help="仅在探测失败时输出")
    args = parser.parse_args()
    try:
        values = read_env(args.env_file)
        base = frontend_base(values)
        context = ssl_context(values)
        frontend_paths = ("/api/health", "/api/health/ready")
        checks = [(f"{base}{path}", f"frontend GET {path}") for path in frontend_paths]
        if not args.frontend_only:
            java_port = setting("JAVA_PORT", values, "8080")
            python_port = setting("PYTHON_PORT", values, "8000")
            checks.extend((
                (f"http://127.0.0.1:{java_port}/api/health/ready", "backend-java GET /api/health/ready"),
                (f"http://127.0.0.1:{python_port}/api/health", "backend-python GET /api/health"),
            ))
    except ValueError as exc:
        print(f"FAIL offline smoke configuration: {exc}", file=sys.stderr)
        return 1

    results = [probe(url, label, args.quiet, context) for url, label in checks]
    if all(results):
        if not args.quiet:
            print("OFFLINE_SMOKE_PASSED")
        return 0
    print("OFFLINE_SMOKE_FAILED", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
