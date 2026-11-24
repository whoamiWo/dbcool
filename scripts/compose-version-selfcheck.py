#!/usr/bin/env python3
"""回归检查生产 / 开发 / 离线 / 蓝绿部署 APP_VERSION 语义与离线安装 fail-closed。"""
from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import yaml
except ImportError as exc:
    raise SystemExit(f"❌ 缺少 PyYAML，无法执行 Compose selfcheck: {exc}")

ROOT = Path(__file__).resolve().parent.parent
APP_SERVICES = ("backend-java", "backend-python", "crdt-service", "frontend")
RELEASE_TAG = "2.1.5-3"
REGISTRY = "ghcr.io/whoamiwo/dbcool"
BASE_IMAGES = ("pgvector/pgvector:pg16", "redis:7.4-alpine",
               "minio/minio:RELEASE.2025-09-07T16-13-09Z", "rabbitmq:3.13.7-management-alpine")


def report(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'✅' if ok else '❌'} {name}{(' —— ' + detail) if detail else ''}")
    return ok


def clean_env(home: Path, **extra: str) -> dict[str, str]:
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(home),
           "COMPOSE_DISABLE_ENV_FILE": "1"}
    env.update(extra)
    return env


def run(command: list[str], *, cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)


def write_test_env(target: Path, tag: str = RELEASE_TAG) -> None:
    source = (ROOT / ".env.dev.example").read_text(encoding="utf-8")
    lines = [f"APP_VERSION={tag}" if line.startswith("APP_VERSION=") else line
             for line in source.splitlines()]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compose_config(compose_file: Path, env_file: Path, home: Path, *extra_files: Path,
                   env: dict[str, str] | None = None,
                   args: tuple[str, ...] = ()) -> subprocess.CompletedProcess[str]:
    command = ["docker", "compose", "--project-directory", str(home), "-f", str(compose_file)]
    for extra_file in extra_files:
        command.extend(("-f", str(extra_file)))
    command.extend(("--env-file", str(env_file)))
    command.extend(args or ("config", "--format", "json"))
    return run(command, cwd=ROOT, env=env or clean_env(home))


def fake_docker_bin(bin_dir: Path, log_path: Path) -> None:
    bin_dir.mkdir(parents=True, exist_ok=True)
    docker = bin_dir / "docker"
    docker.write_text(
        "#!/usr/bin/env bash\n"
        "printf '%s|APP_VERSION=%s\\n' \"$*\" \"${APP_VERSION:-}\" >> \"$MOCK_DOCKER_LOG\"\n"
        "if [[ \"$1\" == compose && \"${2:-}\" == version ]]; then exit 0; fi\n"
        "if [[ \"$1\" == compose ]]; then\n"
        "  args=\" $* \"\n"
        "  if [[ \"$args\" == *\" config --images \"* ]]; then cat \"$MOCK_IMAGES_FILE\"; exit 0; fi\n"
        "  if [[ \"$args\" == *\" ps --all -q \"* ]]; then\n"
        "    for svc in postgres redis minio rabbitmq backend-java backend-python crdt-service frontend; do\n"
        "      if [[ \"$args\" == *\" -q $svc \"* ]]; then\n"
        "        missing_var=MOCK_MISSING_${svc//-/_}; [[ \"${!missing_var:-0}\" == 1 ]] && exit 0\n"
        "        echo \"cid-$svc\"; exit 0\n"
        "      fi\n"
        "    done\n"
        "    exit 0\n"
        "  fi\n"
        "  if [[ \"$args\" == *\" logs --tail \"* ]]; then echo \"${MOCK_LOG_TEXT:-service log summary}\"; exit 0; fi\n"
        "  if [[ \"$args\" == *\" ps --services --filter status=running \"* || \"$args\" == *\" config --services \"* ]]; then printf 'svc\\n%.0s' {1..10}; exit 0; fi\n"
        "  exit 0\n"
        "fi\n"
        "if [[ \"$1\" == exec ]]; then [[ \"$*\" == *curl* ]] && exit 0; echo blue; exit 0; fi\n"
        "if [[ \"$1\" == -s* ]]; then exit 0; fi\n"
        "if [[ \"$1\" == http://* || \"$1\" == https://* ]]; then exit 0; fi\n"

        "if [[ \"$1\" == ps ]]; then echo running; exit 0; fi\n"
        "if [[ \"$1\" == curl ]]; then exit 0; fi\n"

        "if [[ \"$1\" == -f ]]; then echo 'healthy'; exit 0; fi\n"
        "if [[ \"$1\" == image && \"$2\" == inspect ]]; then\n"
        "  case \"${MOCK_IMAGE_POLICY:-all}\" in missing) exit 1;; missing-frontend) [[ \"$*\" == *dbcool-frontend:* ]] && exit 1;; missing-base) [[ \"$*\" == *pgvector* ]] && exit 1;; esac\n"
        "  exit 0\nfi\n"
        "if [[ \"$1\" == inspect ]]; then\n"
        "  for svc in postgres redis minio rabbitmq backend-java backend-python crdt-service frontend; do\n"
        "    if [[ \"$*\" == *cid-$svc* ]]; then\n"
        "      status_var=MOCK_STATUS_${svc//-/_}; status=\"${!status_var:-running}\"\n"
        "      health_var=MOCK_HEALTH_${svc//-/_}; health=\"${!health_var:-healthy}\"\n"
        "      [[ \"$svc\" == frontend ]] && health=none\n"
        "      [[ \"$status\" == exited ]] && health=none\n"
        "      if [[ \"$*\" == *'format {{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'* ]]; then\n"
        "        printf '%s|%s\\n' \"$status\" \"$health\"\n"
        "      else\n"
        "        echo \"${MOCK_HEALTH:-$health}\"\n"
        "      fi\n"
        "      exit 0\n"
        "    fi\n"
        "  done\n"
        "  echo \"${MOCK_HEALTH:-healthy}\"; exit 0\nfi\n"
        "if [[ \"$1\" == save ]]; then\n"
        "  while [[ $# -gt 0 ]]; do if [[ \"$1\" == -o ]]; then shift; : > \"$1\"; exit 0; fi; shift; done\n"
        "fi\n"
        "if [[ \"$1\" == load ]]; then exit 0; fi\n"
        "exit 0\n", encoding="utf-8")
    docker.chmod(0o755)
    sleep = bin_dir / "sleep"
    sleep.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    sleep.chmod(0o755)
    curl = bin_dir / "curl"
    curl.write_text(
        "#!/usr/bin/env bash\n"
        "printf 'curl|%s\\n' \"$*\" >> \"$MOCK_DOCKER_LOG\"\n"
        "[[ \"${MOCK_HEALTH:-}\" == unhealthy ]] && exit 22\n"
        "[[ \"${MOCK_CURL_PORT:-}\" == 8080 ]] && exit 7\n"
        "exit 0\n", encoding="utf-8")
    curl.chmod(0o755)
    log_path.write_text("", encoding="utf-8")


def check_compose_configs(tmp: Path) -> bool:
    ok = True
    env_empty = tmp / "empty.env"
    defaults = (ROOT / ".env.dev.example").read_text(encoding="utf-8").splitlines()
    env_empty.write_text("\n".join(line for line in defaults if not line.startswith("APP_VERSION=")) + "\n",
                         encoding="utf-8")
    production, dev_override = ROOT / "docker-compose.yml", ROOT / "docker-compose.dev-build.yml"
    blue_green, offline = ROOT / "docker-compose.blue-green.yml", ROOT / "packaging/offline/docker-compose.offline.yml"
    missing = compose_config(production, env_empty, tmp, env=clean_env(tmp, APP_VERSION=""),
                             args=("config", "--images"))
    missing_text = missing.stdout + missing.stderr
    ok &= report("主 compose 缺 APP_VERSION 必须失败",
                 missing.returncode != 0 and "APP_VERSION" in missing_text, f"exit={missing.returncode}")
    env_release = tmp / "release.env"
    write_test_env(env_release)
    prod = compose_config(production, env_release, tmp, args=("config", "--images"))
    images = set(prod.stdout.splitlines())
    expected = {f"{REGISTRY}/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES}
    ok &= report("发布 tag 精确解析到四个应用镜像", prod.returncode == 0 and expected.issubset(images),
                 f"exit={prod.returncode}; app_images={len(images & expected)}/4")
    base_data = yaml.safe_load(production.read_text(encoding="utf-8"))
    no_build = all("build" not in base_data["services"][service] for service in APP_SERVICES)
    dev_env = tmp / "dev.env"
    shutil.copyfile(ROOT / ".env.dev.example", dev_env)
    dev = compose_config(production, dev_env, tmp, dev_override)
    dev_data = json.loads(dev.stdout) if dev.returncode == 0 else {}
    dev_builds = all("build" in dev_data.get("services", {}).get(service, {}) for service in APP_SERVICES)
    ok &= report("主 compose 无应用 build；dev override 才启用四个 build",
                 no_build and dev_builds, f"prod_build_free={no_build}; dev_builds={dev_builds}")
    no_to = compose_config(production, env_release, tmp, blue_green,
                           env=clean_env(tmp, APP_VERSION=RELEASE_TAG, TO_TAG=""),
                           args=("--profile", "bg", "config", "--images"))
    ok &= report("blue-green 缺 TO_TAG 必须失败",
                 no_to.returncode != 0 and "TO_TAG" in (no_to.stdout + no_to.stderr),
                 f"exit={no_to.returncode}")
    bg = compose_config(production, env_release, tmp, blue_green,
                        env=clean_env(tmp, APP_VERSION=RELEASE_TAG, TO_TAG="2.1.5-4"),
                        args=("--profile", "bg", "config", "--format", "json"))
    bg_data = json.loads(bg.stdout) if bg.returncode == 0 else {}
    bg_ok = all(bg_data.get("services", {}).get(f"{service}-green", {}).get("image") ==
                f"{REGISTRY}/dbcool-{service}:2.1.5-4"
                for service in ("backend-java", "backend-python", "crdt-service"))
    ok &= report("blue-green 显式 TO_TAG 解析到精确 green 镜像", bg.returncode == 0 and bg_ok,
                 f"exit={bg.returncode}; tags_ok={bg_ok}")
    offline_cfg = compose_config(offline, env_release, tmp, args=("config", "--images"))
    ok &= report("离线 compose 使用所选 APP_VERSION tag",
                 offline_cfg.returncode == 0 and expected.issubset(set(offline_cfg.stdout.splitlines())),
                 f"exit={offline_cfg.returncode}")
    return bool(ok)


def offline_smoke_server(statuses: dict[str, int] | None = None) -> tuple[ThreadingHTTPServer, threading.Thread, int]:
    statuses = statuses or {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            status = statuses.get(self.path, 200)
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b"ok" if status == 200 else b"failure")

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, int(server.server_address[1])


def check_offline_package(tmp: Path) -> bool:
    ok = True
    fixture = tmp / "offline-fixture"
    (fixture / "scripts").mkdir(parents=True)
    (fixture / "packaging/offline").mkdir(parents=True)
    (fixture / "dist").mkdir()
    for rel in ("scripts/package-offline.sh", "scripts/offline-smoke.py", "docker-compose.yml",
                "packaging/offline/install-offline.sh"):
        target = fixture / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / rel, target)
    bin_dir, docker_log = tmp / "offline-bin", tmp / "offline-docker.log"
    fake_docker_bin(bin_dir, docker_log)
    images_file = tmp / "package-images.txt"
    images_file.write_text("", encoding="utf-8")
    base_env = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                         MOCK_DOCKER_LOG=str(docker_log), MOCK_IMAGES_FILE=str(images_file),
                         MOCK_IMAGE_POLICY="missing")
    missing = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture,
                  env={**base_env, "APP_VERSION": ""})
    ok &= report("离线打包缺 APP_VERSION 返回 exit 2", missing.returncode == 2 and "APP_VERSION" in missing.stderr,
                 f"exit={missing.returncode}")
    missing_images = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture,
                         env={**base_env, "APP_VERSION": RELEASE_TAG})
    ok &= report("离线打包缺本地镜像时失败", missing_images.returncode == 2 and "镜像缺失" in missing_images.stderr,
                 f"exit={missing_images.returncode}")
    image_names = [f"{REGISTRY}/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES]
    image_names.extend(("pgvector/pgvector:pg16", "redis:7.4-alpine",
                        "minio/minio:RELEASE.2025-09-07T16-13-09Z", "rabbitmq:3.13.7-management-alpine"))
    images_file.write_text("\n".join(image_names) + "\n", encoding="utf-8")
    success = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture,
                  env={**base_env, "MOCK_IMAGE_POLICY": "all", "APP_VERSION": RELEASE_TAG})
    out = fixture / "packaging/offline"
    sample, generated_compose = out / ".env.example", out / "docker-compose.offline.yml"
    archive = fixture / "dist" / f"dbcool-offline-{RELEASE_TAG}.tar.gz"
    generated_ok = success.returncode == 0 and sample.exists() and generated_compose.exists() \
        and (out / "offline-smoke.py").exists() and (out / "image-tags.txt").exists() and archive.exists()
    if generated_ok:
        generated_ok &= f"APP_VERSION={RELEASE_TAG}" in sample.read_text(encoding="utf-8")
        generated_ok &= set((out / "image-tags.txt").read_text(encoding="utf-8").splitlines()) == set(image_names)
        sums = {line.split(None, 1)[1].lstrip("* ").strip()
                for line in (out / "SHA256SUMS").read_text(encoding="utf-8").splitlines()}
        generated_ok &= {"offline-smoke.py", "image-tags.txt"} <= sums
        parsed = yaml.safe_load(generated_compose.read_text(encoding="utf-8"))
        generated_ok &= all("${APP_VERSION:?" in parsed["services"][service]["image"] for service in APP_SERVICES)
        tar_result = subprocess.run(["tar", "-tzf", str(archive)], text=True, capture_output=True, check=False)
        generated_ok &= tar_result.returncode == 0 and "offline/offline-smoke.py" in tar_result.stdout \
            and "offline/image-tags.txt" in tar_result.stdout
    ok &= report("离线包包含 smoke/镜像清单且 SHA256 与 tar 覆盖", generated_ok,
                 f"exit={success.returncode}; stderr={success.stderr.strip()}")
    return bool(ok)


def check_offline_installer(tmp: Path) -> bool:
    ok = True
    bin_dir, docker_log = tmp / "installer-bin", tmp / "installer-docker.log"
    fake_docker_bin(bin_dir, docker_log)
    http_ok, _, http_port = offline_smoke_server()
    http_bad, _, bad_port = offline_smoke_server({"/api/health/ready": 503})
    try:
        def fixture(name: str, *, include_env: bool = True, credentials: bool = True,
                    smoke_port: int | None = None) -> Path:
            base = tmp / name
            base.mkdir()
            for target, source in (("install-offline.sh", ROOT / "packaging/offline/install-offline.sh"),
                                   ("docker-compose.offline.yml", ROOT / "packaging/offline/docker-compose.offline.yml"),
                                   ("offline-smoke.py", ROOT / "scripts/offline-smoke.py")):
                shutil.copyfile(source, base / target)
            shutil.copyfile(ROOT / "packaging/offline/.env.example", base / ".env.example")
            (base / "images.tar").write_bytes(b"selfcheck image archive")
            tags = [f"{REGISTRY}/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES]
            tags.extend(BASE_IMAGES)
            (base / "image-tags.txt").write_text("\n".join(tags) + "\n", encoding="utf-8")
            test_port = smoke_port or http_port
            lines = [f"APP_VERSION={RELEASE_TAG}", f"IMAGE_REGISTRY={REGISTRY}",
                     "POSTGRES_USER=testpg", "POSTGRES_PASSWORD=pg-unique-secret",
                     "MINIO_ROOT_USER=testminio", "MINIO_ROOT_PASSWORD=minio-unique-secret",
                     "RABBITMQ_USER=testrabbit", "RABBITMQ_PASSWORD=rabbit-unique-secret",
                     "JWT_SECRET=" + base64.b64encode(b"J" * 32).decode(),
                     "INTERNAL_SERVICE_TOKEN=internal-unique-secret",
                     "INTEGRATION_CREDENTIAL_MASTER_KEY=" + base64.b64encode(b"K" * 32).decode(),
                     f"FRONTEND_PORT={test_port}", f"JAVA_PORT={test_port}", f"PYTHON_PORT={test_port}"]
            if not credentials:
                lines[3] = "POSTGRES_PASSWORD=dev_password"
            if include_env:
                (base / ".env").write_text("\n".join(lines) + "\n", encoding="utf-8")
            filenames = ("images.tar", "docker-compose.offline.yml", ".env.example", "install-offline.sh",
                         "offline-smoke.py", "image-tags.txt")
            sums = [f"{hashlib.sha256((base / name).read_bytes()).hexdigest()}  {name}" for name in filenames]
            (base / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
            return base

        mock_images = tmp / "installer-images.txt"
        mock_images.write_text("\n".join(f"{REGISTRY}/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES)
                               + "\n", encoding="utf-8")
        env_base = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                             MOCK_DOCKER_LOG=str(docker_log), MOCK_IMAGES_FILE=str(mock_images),
                             NON_INTERACTIVE="1", OFFLINE_HEALTH_TIMEOUT_SECONDS="0")
        no_env = fixture("installer-no-env-ni", include_env=False)
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=no_env, env=env_base)
        calls = docker_log.read_text(encoding="utf-8")
        ok &= report("非交互缺 .env 在 Docker 命令前 exit 2", result.returncode == 2
                     and "CONFIG_REQUIRED" in result.stderr and not calls and not (no_env / ".env").exists(),
                     f"exit={result.returncode}; calls={calls!r}")
        no_env_interactive = fixture("installer-no-env-interactive", include_env=False)
        interactive = run(["bash", "install-offline.sh"], cwd=no_env_interactive,
                          env={key: value for key, value in env_base.items() if key != "NON_INTERACTIVE"})
        ok &= report("交互首次运行只生成模板并返回 CONFIG_REQUIRED", interactive.returncode == 2
                     and "CONFIG_REQUIRED" in interactive.stderr and (no_env_interactive / ".env").exists(),
                     f"exit={interactive.returncode}")
        verify = fixture("installer-verify", include_env=False)
        verified = run(["bash", "install-offline.sh", "--verify"], cwd=verify, env=env_base)
        verify_calls = docker_log.read_text(encoding="utf-8")
        ok &= report("--verify 只读模板並區分包驗證與部署驗證", verified.returncode == 0
                     and "PACKAGE_VERIFIED" in verified.stdout and "DEPLOYMENT_NOT_VERIFIED" in verified.stdout
                     and "load" not in verify_calls and "up -d" not in verify_calls
                     and not (verify / ".env").exists() and "安装完成" not in verified.stdout,
                     f"exit={verified.returncode}")
        placeholder = fixture("installer-placeholder", credentials=False)
        docker_log.write_text("", encoding="utf-8")
        rejected = run(["bash", "install-offline.sh"], cwd=placeholder, env=env_base)
        calls = docker_log.read_text(encoding="utf-8")
        ok &= report("占位口令在 docker load 前拒绝且不回显 secret", rejected.returncode == 2
                     and "POSTGRES_PASSWORD" in rejected.stderr and "load" not in calls
                     and "pg-unique-secret" not in rejected.stdout + rejected.stderr,
                     f"exit={rejected.returncode}")
        verify_placeholder = fixture("installer-verify-placeholder", credentials=False)
        docker_log.write_text("", encoding="utf-8")
        verified_placeholder = run(["bash", "install-offline.sh", "--verify"], cwd=verify_placeholder,
                                   env=env_base)
        verify_calls = docker_log.read_text(encoding="utf-8")
        ok &= report("--verify 对占位凭证只报告包验证、不声称部署可用", verified_placeholder.returncode == 0
                     and "PACKAGE_VERIFIED" in verified_placeholder.stdout
                     and "DEPLOYMENT_NOT_VERIFIED" in verified_placeholder.stdout
                     and "load" not in verify_calls and "up -d" not in verify_calls
                     and "安装完成" not in verified_placeholder.stdout,
                     f"exit={verified_placeholder.returncode}")
        tampered = fixture("installer-sha256-mismatch")
        (tampered / "docker-compose.offline.yml").write_bytes(b"tampered compose file")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=tampered, env=env_base)
        ok &= report("SHA256 不匹配时不加载镜像", result.returncode == 2
                     and "SHA256" in result.stderr and "load" not in docker_log.read_text(encoding="utf-8"),
                     f"exit={result.returncode}")
        short_jwt = fixture("installer-short-jwt")
        jwt_text = (short_jwt / ".env").read_text(encoding="utf-8")
        jwt_text = jwt_text.replace("JWT_SECRET=" + base64.b64encode(b"J" * 32).decode(),
                                    "JWT_SECRET=" + base64.b64encode(b"J" * 8).decode())
        (short_jwt / ".env").write_text(jwt_text, encoding="utf-8")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=short_jwt, env=env_base)
        ok &= report("JWT_SECRET 解码后不足 32 字节在 load 前拒绝", result.returncode == 2
                     and "JWT_SECRET" in result.stderr
                     and "load" not in docker_log.read_text(encoding="utf-8"),
                     f"exit={result.returncode}")
        bad_master = fixture("installer-bad-master-key")
        master_text = (bad_master / ".env").read_text(encoding="utf-8")
        master_text = master_text.replace(
            "INTEGRATION_CREDENTIAL_MASTER_KEY=" + base64.b64encode(b"K" * 32).decode(),
            "INTEGRATION_CREDENTIAL_MASTER_KEY=" + base64.b64encode(b"K" * 16).decode())
        (bad_master / ".env").write_text(master_text, encoding="utf-8")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=bad_master, env=env_base)
        ok &= report("集成主密钥非 32 字节在 load 前拒绝", result.returncode == 2
                     and "INTEGRATION_CREDENTIAL_MASTER_KEY" in result.stderr
                     and "load" not in docker_log.read_text(encoding="utf-8"),
                     f"exit={result.returncode}")
        mismatch = fixture("installer-missing-image")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=mismatch,
                     env={**env_base, "MOCK_IMAGE_POLICY": "missing-frontend"})
        ok &= report("缺精确镜像 tag 时 up 前失败", result.returncode == 2
                     and "dbcool-frontend" in result.stderr and "up -d" not in docker_log.read_text(),
                     f"exit={result.returncode}")
        healthy = fixture("installer-healthy")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=healthy, env=env_base)
        calls = docker_log.read_text(encoding="utf-8")
        checked = all(f"-q {service}" in calls for service in ("postgres", "redis", "minio", "rabbitmq", *APP_SERVICES))
        ok &= report("逐项容器健康 + frontend 双 HTTP 探针 + 完整 smoke 才通过",
                     result.returncode == 0 and "OFFLINE_SMOKE_PASSED" in result.stdout and checked
                     and " up -d --no-build" in calls,
                     f"exit={result.returncode}; checked={checked}")
        cases = (("unhealthy", {"MOCK_HEALTH_postgres": "unhealthy"}),
                 ("starting", {"MOCK_HEALTH_redis": "starting"}),
                 ("exited", {"MOCK_STATUS_minio": "exited"}),
                 ("missing", {"MOCK_MISSING_rabbitmq": "1"}))
        for label, extra in cases:
            case = fixture(f"installer-{label}")
            docker_log.write_text("", encoding="utf-8")
            result = run(["bash", "install-offline.sh"], cwd=case,
                         env={**env_base, **extra, "MOCK_LOG_TEXT": "diagnostic pg-unique-secret"})
            calls = docker_log.read_text(encoding="utf-8")
            ok &= report(f"健康状态 {label} fail-closed 且输出脱敏日志摘要",
                         result.returncode == 1 and "安装完成" not in result.stdout
                         and "logs --tail 40" in calls and "diagnostic [REDACTED]" in result.stdout
                         and "pg-unique-secret" not in result.stdout + result.stderr,
                         f"exit={result.returncode}; failure={result.stderr.strip()}")
        bad = fixture("installer-frontend-bad", smoke_port=bad_port)
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=bad, env=env_base)
        ok &= report("frontend readiness HTTP 非 200 即失败", result.returncode == 1
                     and "frontend HTTP readiness" in result.stderr, f"exit={result.returncode}")
        missing_smoke = fixture("installer-no-smoke")
        (missing_smoke / "offline-smoke.py").unlink()
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=missing_smoke, env=env_base)
        ok &= report("离线包缺 smoke 脚本时拒绝启动", result.returncode == 2
                     and "SHA256 校验失败" in result.stderr and "load" not in docker_log.read_text(),
                     f"exit={result.returncode}")
        legal = fixture("installer-legal-usernames")
        legal_env = (legal / ".env").read_text(encoding="utf-8")
        for old_line, new_line in (("POSTGRES_USER=testpg", "POSTGRES_USER=nocobase"),
                                   ("MINIO_ROOT_USER=testminio", "MINIO_ROOT_USER=minio"),
                                   ("RABBITMQ_USER=testrabbit", "RABBITMQ_USER=rabbitmq")):
            legal_env = legal_env.replace(old_line, new_line)
        (legal / ".env").write_text(legal_env, encoding="utf-8")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=legal, env=env_base)
        ok &= report("合法用户名 nocobase/minio/rabbitmq 不被当作占位值拒绝", result.returncode == 0
                     and "OFFLINE_SMOKE_PASSED" in result.stdout, f"exit={result.returncode}")
        missing_base = fixture("installer-missing-base")
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=missing_base,
                     env={**env_base, "MOCK_IMAGE_POLICY": "missing-base"})
        calls = docker_log.read_text(encoding="utf-8")
        ok &= report("缺精确基础镜像 tag 时 up 前失败", result.returncode == 2
                     and "pgvector/pgvector:pg16" in result.stderr and "up -d" not in calls,
                     f"exit={result.returncode}")
        no_python = fixture("installer-no-python")
        tools = tmp / "no-python-tools"
        tools.mkdir()
        for tool in ("bash", "dirname"):
            resolved = shutil.which(tool)
            if resolved:
                (tools / tool).symlink_to(resolved)
        docker_log.write_text("", encoding="utf-8")
        result = run(["bash", "install-offline.sh"], cwd=no_python,
                     env={**env_base, "PATH": f"{tools}:{bin_dir}"})
        ok &= report("缺少 python3 时明确失败且不调用 Docker", result.returncode == 2
                     and "需要 Python 3" in result.stderr and not docker_log.read_text(),
                     f"exit={result.returncode}; stderr={result.stderr.strip()}")
    finally:
        http_ok.shutdown()
        http_bad.shutdown()
    return bool(ok)


def check_offline_smoke_tls(tmp: Path) -> bool:
    ok = True
    cert_dir = tmp / "tls-certs"
    cert_dir.mkdir()
    key, cert = cert_dir / "key.pem", cert_dir / "fullchain.pem"
    generated = subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
         "-keyout", str(key), "-out", str(cert), "-subj", "/CN=127.0.0.1",
         "-addext", "subjectAltName=IP:127.0.0.1"],
        text=True, capture_output=True, check=False)
    if generated.returncode != 0:
        return report("自签证书生成（openssl 可用）", False, generated.stderr.strip()[:200])

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(cert), str(key))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    port = int(server.server_address[1])
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        smoke = ROOT / "scripts/offline-smoke.py"
        env_file = tmp / "tls-smoke.env"
        env_file.write_text(f"SMOKE_BASE_URL=https://127.0.0.1:{port}\n"
                            f"SMOKE_CA_BUNDLE={cert}\n", encoding="utf-8")
        trusted = run([sys.executable, str(smoke), "--env-file", str(env_file),
                       "--frontend-only", "--quiet"], cwd=tmp, env=clean_env(tmp))
        ok &= report("SMOKE_CA_BUNDLE 验证自签 TLS 健康端点", trusted.returncode == 0,
                     f"exit={trusted.returncode}; stderr={trusted.stderr.strip()}")
        env_no_ca = tmp / "tls-smoke-no-ca.env"
        env_no_ca.write_text(f"SMOKE_BASE_URL=https://127.0.0.1:{port}\n", encoding="utf-8")
        untrusted = run([sys.executable, str(smoke), "--env-file", str(env_no_ca),
                         "--frontend-only", "--quiet"], cwd=tmp, env=clean_env(tmp))
        ok &= report("未提供 CA 时自签 TLS 不被接受（不关闭校验）", untrusted.returncode == 1,
                     f"exit={untrusted.returncode}")
        packaged_dir = tmp / "packaged-smoke"
        (packaged_dir / "certs").mkdir(parents=True)
        shutil.copyfile(smoke, packaged_dir / "offline-smoke.py")
        shutil.copyfile(cert, packaged_dir / "certs" / "fullchain.pem")
        packaged = run([sys.executable, str(packaged_dir / "offline-smoke.py"),
                        "--env-file", str(env_no_ca), "--frontend-only", "--quiet"],
                       cwd=tmp, env=clean_env(tmp))
        ok &= report("包内 certs/fullchain.pem 作为默认 CA", packaged.returncode == 0,
                     f"exit={packaged.returncode}")
        spec = importlib.util.spec_from_file_location("offline_smoke_module", smoke)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        bind_base = module.frontend_base({"FRONTEND_BIND_ADDR": "192.168.10.7", "FRONTEND_PORT": "8080"})
        wildcard = module.frontend_base({"FRONTEND_BIND_ADDR": "0.0.0.0", "FRONTEND_PORT": "8080"})
        ok &= report("FRONTEND_BIND_ADDR 非回环优先、通配地址回退 127.0.0.1",
                     bind_base == "http://192.168.10.7:8080" and wildcard == "http://127.0.0.1:8080",
                     f"bind={bind_base}; wildcard={wildcard}")
    finally:
        server.shutdown()
    return bool(ok)


def check_deploy_scripts(tmp: Path) -> bool:
    ok = True
    fixture = tmp / "deploy-fixture"
    (fixture / "scripts").mkdir(parents=True)
    for rel in ("deploy.sh", "docker-compose.yml", "docker-compose.observability.yml"):
        shutil.copyfile(ROOT / rel, fixture / rel)
    bin_dir, docker_log = tmp / "deploy-bin", tmp / "deploy-docker.log"
    fake_docker_bin(bin_dir, docker_log)
    env = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                    MOCK_DOCKER_LOG=str(docker_log), MOCK_IMAGES_FILE=str(tmp / "none"),
                    DEPLOY_LOG_FILE=str(tmp / "deploy.log"))
    required = ("POSTGRES_PASSWORD=long-strong-postgres-password", "MINIO_ROOT_PASSWORD=long-strong-minio-password",
                "RABBITMQ_USER=nocobase", "RABBITMQ_PASSWORD=long-strong-rabbit-password",
                "INTEGRATION_CREDENTIAL_MASTER_KEY=local-test-master-key", "FRONTEND_PORT=18080")
    (fixture / ".env").write_text("\n".join(required) + "\n", encoding="utf-8")
    missing = run(["bash", "deploy.sh", "prod"], cwd=fixture, env=env)
    calls = docker_log.read_text(encoding="utf-8")
    ok &= report("deploy prod 缺 APP_VERSION 在 Docker 写操作前失败",
                 missing.returncode == 2 and "compose pull" not in calls and "compose up" not in calls,
                 f"exit={missing.returncode}")

    def run_prod(tag: str) -> subprocess.CompletedProcess[str]:
        (fixture / ".env").write_text("APP_VERSION=" + tag + "\n" + "\n".join(required) + "\n",
                                     encoding="utf-8")
        docker_log.write_text("", encoding="utf-8")
        return run(["bash", "deploy.sh", "prod"], cwd=fixture,
                   env={**env, "APP_VERSION": "", "MOCK_DOCKER_LOG": str(docker_log)})

    first = run_prod("2.1.5-2")
    calls = docker_log.read_text(encoding="utf-8")
    ops = [line.split("|", 1)[0] for line in calls.splitlines()]
    first_ok = (first.returncode == 0 and (fixture / ".last-deployed-tag").read_text().strip() == "2.1.5-2"
                and any("compose pull" in line for line in ops)
                and any("compose up -d --no-build" in line for line in ops)
                and not any("compose up -d --build" in line for line in ops))
    ok &= report("deploy prod 使用 pull + up --no-build 并记录 tag", first_ok,
                 f"exit={first.returncode}; stderr={first.stderr.strip()}; stdout={first.stdout.strip()}; calls={calls.strip()}")
    second = run_prod(RELEASE_TAG)
    previous = ((fixture / ".prev-deployed-tag").read_text(encoding="utf-8").strip()
                if (fixture / ".prev-deployed-tag").exists() else "")
    ok &= report("deploy prod 保存上一版镜像 tag", second.returncode == 0 and previous == "2.1.5-2",
                 f"exit={second.returncode}; previous={previous}")
    docker_log.write_text("", encoding="utf-8")
    rollback = run(["bash", "deploy.sh", "rollback"], cwd=fixture,
                   env={**env, "MOCK_DOCKER_LOG": str(docker_log)})
    calls = docker_log.read_text(encoding="utf-8")
    rollback_ok = (rollback.returncode == 0
                   and (fixture / ".last-deployed-tag").read_text().strip() == "2.1.5-2"
                   and (fixture / ".env").read_text().startswith("APP_VERSION=2.1.5-2\n")
                   and any("compose pull" in line.split("|", 1)[0] and "APP_VERSION=2.1.5-2" in line
                           for line in calls.splitlines())
                   and any("compose up -d --no-build" in line.split("|", 1)[0] and "APP_VERSION=2.1.5-2" in line
                           for line in calls.splitlines()))
    ok &= report("deploy rollback 恢复上一版 APP_VERSION + --no-build", rollback_ok,
                 f"exit={rollback.returncode}")

    upgrade = tmp / "upgrade-fixture"
    (upgrade / "scripts").mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/upgrade.sh", upgrade / "scripts/upgrade.sh")
    shutil.copyfile(ROOT / "docker-compose.yml", upgrade / "docker-compose.yml")
    (upgrade / ".env").write_text("APP_VERSION=2.1.5-2\n", encoding="utf-8")
    docker_log.write_text("", encoding="utf-8")
    failed_upgrade = run(["bash", "scripts/upgrade.sh", "--from", "2.1.5-2", "--to", RELEASE_TAG, "--skip-backup"],
                         cwd=upgrade, env={**env, "MOCK_DOCKER_LOG": str(docker_log),
                                           "MOCK_HEALTH": "unhealthy", "UPGRADE_HEALTH_TIMEOUT_SECONDS": "0"})
    calls = docker_log.read_text(encoding="utf-8")
    failure_ok = (failed_upgrade.returncode == 1
                  and (upgrade / ".last-deployed-tag").read_text().strip() == "2.1.5-2"
                  and (upgrade / ".env").read_text().strip() == "APP_VERSION=2.1.5-2"
                  and any("pull backend-java backend-python crdt-service frontend" in line.split("|", 1)[0]
                          and "APP_VERSION=2.1.5-3" in line for line in calls.splitlines())
                  and any("up -d --no-build backend-java backend-python crdt-service frontend" in line.split("|", 1)[0]
                          and "APP_VERSION=2.1.5-3" in line for line in calls.splitlines())
                  and any("pull backend-java backend-python crdt-service frontend" in line.split("|", 1)[0]
                          and "APP_VERSION=2.1.5-2" in line for line in calls.splitlines())
                  and any("up -d --no-build backend-java backend-python crdt-service frontend" in line.split("|", 1)[0]
                          and "APP_VERSION=2.1.5-2" in line for line in calls.splitlines()))
    ok &= report("upgrade 失败时以 --from APP_VERSION + --no-build 回滚", failure_ok,
                 f"exit={failed_upgrade.returncode}")
    bg = tmp / "blue-green-fixture"
    (bg / "scripts").mkdir(parents=True)
    for rel in ("scripts/blue-green-deploy.sh", "docker-compose.yml", "docker-compose.blue-green.yml"):
        shutil.copyfile(ROOT / rel, bg / rel)
    write_test_env(bg / ".env")
    no_to = run(["bash", "scripts/blue-green-deploy.sh"], cwd=bg, env=env)
    ok &= report("blue-green 未传 --to 返回失败", no_to.returncode == 2)
    docker_log.write_text("", encoding="utf-8")
    dry_run = run(["bash", "scripts/blue-green-deploy.sh", "--dry-run", "--to", RELEASE_TAG],
                  cwd=bg, env={**env, "MOCK_DOCKER_LOG": str(docker_log)})
    dry_ok = (dry_run.returncode == 0 and f"TO_TAG={RELEASE_TAG}" in dry_run.stdout
              and "pull backend-java-green backend-python-green crdt-service-green" in dry_run.stdout
              and "up -d --no-build backend-java-green" in dry_run.stdout)
    ok &= report("blue-green 显式 --to 生成精确 pull / --no-build 命令", dry_ok,
                 f"exit={dry_run.returncode}")
    return bool(ok)


def main() -> int:
    if shutil.which("docker") is None:
        print("❌ 缺少 docker CLI，Compose 配置自检无法运行", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory(prefix="dbcool-compose-selfcheck-") as temp_dir:
        tmp = Path(temp_dir)
        print("=== Compose interpolation and build separation ===")
        compose_ok = check_compose_configs(tmp)
        print("\n=== Offline package guard and generation ===")
        package_ok = check_offline_package(tmp)
        print("\n=== Offline installer fail-closed matrix ===")
        installer_ok = check_offline_installer(tmp)
        print("\n=== Offline smoke TLS and bind address ===")
        tls_ok = check_offline_smoke_tls(tmp)
        print("\n=== Production deploy / rollback / blue-green controls ===")
        deploy_ok = check_deploy_scripts(tmp)
    if compose_ok and package_ok and installer_ok and tls_ok and deploy_ok:
        print("\n✅ compose-version-selfcheck 全部通过")
        return 0
    print("\n❌ compose-version-selfcheck 存在失败项")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
