#!/usr/bin/env python3
"""回归检查生产 / 开发 / 离线 / 蓝绿部署的 APP_VERSION 语义。

本脚本只在临时目录生成夹具，不启动容器、不修改真实部署状态。
Docker Compose 用于验证配置插值；Docker CLI 通过临时 mock 测试脚本控制流。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError as exc:  # package-offline.sh 本身也依赖 PyYAML
    raise SystemExit(f"❌ 缺少 PyYAML，无法执行 Compose selfcheck: {exc}")

ROOT = Path(__file__).resolve().parent.parent
APP_SERVICES = ("backend-java", "backend-python", "crdt-service", "frontend")
RELEASE_TAG = "2.1.5-3"


def report(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'✅' if ok else '❌'} {name}{(' —— ' + detail) if detail else ''}")
    return ok


def clean_env(home: Path, **extra: str) -> dict[str, str]:
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": str(home),
        "COMPOSE_DISABLE_ENV_FILE": "1",
    }
    env.update(extra)
    return env


def run(command: list[str], *, cwd: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, check=False)


def write_test_env(target: Path, tag: str = RELEASE_TAG) -> None:
    source = (ROOT / ".env.dev.example").read_text(encoding="utf-8")
    lines = [f"APP_VERSION={tag}" if line.startswith("APP_VERSION=") else line for line in source.splitlines()]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compose_config(compose_file: Path, env_file: Path, home: Path, *extra_files: Path,
                   env: dict[str, str] | None = None, args: tuple[str, ...] = ()) -> subprocess.CompletedProcess[str]:
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
        "if [[ \"$1\" == \"exec\" ]]; then echo blue; exit 0; fi\n"
        "if [[ \"$1\" == \"image\" && \"$2\" == \"inspect\" ]]; then\n"
        "  case \"${MOCK_IMAGE_POLICY:-all}\" in\n"
        "    missing) exit 1 ;;\n"
        "    missing-frontend) [[ \"$*\" == *dbcool-frontend:* ]] && exit 1 ;;\n"
        "  esac\n"
        "  exit 0\n"
        "fi\n"
        "if [[ \"$1\" == \"inspect\" ]]; then echo \"${MOCK_HEALTH:-healthy}\"; exit 0; fi\n"
        "if [[ \"$1\" == \"save\" ]]; then\n"
        "  while [[ $# -gt 0 ]]; do if [[ \"$1\" == \"-o\" ]]; then shift; : > \"$1\"; exit 0; fi; shift; done\n"
        "fi\n"
        "if [[ \"$*\" == *\"ps --services --filter status=running\"* || \"$*\" == *\"config --services\"* ]]; then\n"
        "  printf 'svc\\n%.0s' {1..10}; exit 0\n"
        "fi\n"
        "exit 0\n",
        encoding="utf-8",
    )
    docker.chmod(0o755)
    curl = bin_dir / "curl"
    curl.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    curl.chmod(0o755)
    sleep = bin_dir / "sleep"
    sleep.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    sleep.chmod(0o755)
    log_path.write_text("", encoding="utf-8")


def check_compose_configs(tmp: Path) -> bool:
    ok = True
    env_empty = tmp / "empty.env"
    dev_defaults = (ROOT / ".env.dev.example").read_text(encoding="utf-8").splitlines()
    env_empty.write_text(
        "\n".join(line for line in dev_defaults if not line.startswith("APP_VERSION=")) + "\n",
        encoding="utf-8",
    )
    production = ROOT / "docker-compose.yml"
    dev_override = ROOT / "docker-compose.dev-build.yml"
    blue_green = ROOT / "docker-compose.blue-green.yml"
    offline = ROOT / "packaging/offline/docker-compose.offline.yml"

    missing = compose_config(production, env_empty, tmp, env=clean_env(tmp, APP_VERSION=""),
                             args=("config", "--images"))
    missing_text = missing.stdout + missing.stderr
    ok &= report(
        "主 compose 缺 APP_VERSION 必须失败",
        missing.returncode != 0 and "APP_VERSION" in missing_text,
        f"exit={missing.returncode}; error={missing_text.strip()}",
    )

    env_release = tmp / "release.env"
    write_test_env(env_release)
    prod = compose_config(production, env_release, tmp, args=("config", "--images"))
    images = set(prod.stdout.splitlines())
    expected = {f"ghcr.io/whoamiwo/dbcool/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES}
    ok &= report(
        "发布 tag 精确解析到四个应用镜像",
        prod.returncode == 0 and expected.issubset(images),
        f"exit={prod.returncode}; app_images={len(images & expected)}/4",
    )

    base_data = yaml.safe_load(production.read_text(encoding="utf-8"))
    no_prod_build = all("build" not in base_data["services"][name] for name in APP_SERVICES)
    dev_env = tmp / "dev.env"
    shutil.copyfile(ROOT / ".env.dev.example", dev_env)
    dev = compose_config(production, dev_env, tmp, dev_override)
    dev_data = {}
    if dev.returncode == 0:
        import json
        dev_data = json.loads(dev.stdout)
    dev_builds = all("build" in dev_data.get("services", {}).get(name, {}) for name in APP_SERVICES)
    ok &= report("主 compose 无应用 build；dev override 才启用四个 build", no_prod_build and dev_builds,
                 f"prod_build_free={no_prod_build}; dev_builds={dev_builds}; dev_exit={dev.returncode}")

    no_to = compose_config(production, env_release, tmp, blue_green,
                           env=clean_env(tmp, APP_VERSION=RELEASE_TAG, TO_TAG=""),
                           args=("--profile", "bg", "config", "--images"))
    ok &= report("blue-green 缺 TO_TAG 必须失败", no_to.returncode != 0 and "TO_TAG" in (no_to.stdout + no_to.stderr),
                 f"exit={no_to.returncode}")
    bg = compose_config(production, env_release, tmp, blue_green,
                        env=clean_env(tmp, APP_VERSION=RELEASE_TAG, TO_TAG="2.1.5-4"),
                        args=("--profile", "bg", "config", "--format", "json"))
    bg_data = json.loads(bg.stdout) if bg.returncode == 0 else {}
    bg_green_expected = {
        service: f"ghcr.io/whoamiwo/dbcool/dbcool-{service}:2.1.5-4"
        for service in ("backend-java", "backend-python", "crdt-service")
    }
    bg_tags_ok = all(bg_data.get("services", {}).get(f"{service}-green", {}).get("image") == image
                     for service, image in bg_green_expected.items())
    ok &= report("blue-green 显式 TO_TAG 覆盖 APP_VERSION 并解析到精确 green 镜像",
                 bg.returncode == 0 and bg_tags_ok,
                 f"exit={bg.returncode}; tags_ok={bg_tags_ok}")

    offline_cfg = compose_config(offline, env_release, tmp, args=("config", "--images"))
    offline_images = set(offline_cfg.stdout.splitlines())
    ok &= report("离线 compose 使用所选 APP_VERSION tag",
                 offline_cfg.returncode == 0 and expected.issubset(offline_images),
                 f"exit={offline_cfg.returncode}")
    return bool(ok)


def check_offline_package(tmp: Path) -> bool:
    ok = True
    fixture = tmp / "offline-fixture"
    (fixture / "scripts").mkdir(parents=True)
    (fixture / "packaging/offline").mkdir(parents=True)
    (fixture / "dist").mkdir()
    shutil.copyfile(ROOT / "scripts/package-offline.sh", fixture / "scripts/package-offline.sh")
    shutil.copyfile(ROOT / "docker-compose.yml", fixture / "docker-compose.yml")
    shutil.copyfile(ROOT / "packaging/offline/install-offline.sh", fixture / "packaging/offline/install-offline.sh")
    bin_dir = tmp / "offline-bin"
    docker_log = tmp / "offline-docker.log"
    fake_docker_bin(bin_dir, docker_log)
    base_env = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                         MOCK_DOCKER_LOG=str(docker_log), MOCK_IMAGE_POLICY="missing")

    missing = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture,
                  env={k: v for k, v in base_env.items() if k != "APP_VERSION"})
    ok &= report("离线打包缺 APP_VERSION 返回 exit 2", missing.returncode == 2 and "APP_VERSION" in missing.stderr,
                 f"exit={missing.returncode}")

    missing_images = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture,
                         env={**base_env, "APP_VERSION": RELEASE_TAG})
    ok &= report("离线打包缺本地镜像时失败", missing_images.returncode == 2 and "镜像缺失" in missing_images.stderr,
                 f"exit={missing_images.returncode}")

    success_env = {**base_env, "MOCK_IMAGE_POLICY": "all", "APP_VERSION": RELEASE_TAG}
    success = run(["bash", "scripts/package-offline.sh", "--skip-build"], cwd=fixture, env=success_env)
    sample = (fixture / "packaging/offline/.env.example")
    generated_compose = fixture / "packaging/offline/docker-compose.offline.yml"
    generated_ok = success.returncode == 0 and sample.exists() and generated_compose.exists()
    if generated_ok:
        generated_ok = f"APP_VERSION={RELEASE_TAG}" in sample.read_text(encoding="utf-8")
        parsed = yaml.safe_load(generated_compose.read_text(encoding="utf-8"))
        for service in APP_SERVICES:
            image = parsed["services"][service]["image"]
            generated_ok &= "${APP_VERSION:?" in image
    ok &= report("指定 tag 离线打包后 env 与 compose 一致", generated_ok, f"exit={success.returncode}")
    return bool(ok)


def check_offline_installer(tmp: Path) -> bool:
    ok = True
    bin_dir = tmp / "installer-bin"
    docker_log = tmp / "installer-docker.log"
    fake_docker_bin(bin_dir, docker_log)

    def fixture(name: str, include_version: bool, image_policy: str) -> Path:
        base = tmp / name
        base.mkdir()
        shutil.copyfile(ROOT / "packaging/offline/install-offline.sh", base / "install-offline.sh")
        shutil.copyfile(ROOT / "packaging/offline/docker-compose.offline.yml", base / "docker-compose.offline.yml")
        shutil.copyfile(ROOT / "packaging/offline/.env.example", base / ".env.example")
        (base / "images.tar").write_bytes(b"selfcheck placeholder image archive")
        env_lines = ["IMAGE_REGISTRY=ghcr.io/whoamiwo/dbcool", "POSTGRES_PASSWORD=test", "JWT_SECRET=test"]
        if include_version:
            env_lines.append(f"APP_VERSION={RELEASE_TAG}")
        (base / ".env").write_text("\n".join(env_lines) + "\n", encoding="utf-8")
        import hashlib
        files = ("images.tar", "docker-compose.offline.yml", ".env.example", "install-offline.sh")
        sums = []
        for filename in files:
            digest = hashlib.sha256((base / filename).read_bytes()).hexdigest()
            sums.append(f"{digest}  {filename}")
        (base / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
        return base

    env_base = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                         MOCK_DOCKER_LOG=str(docker_log), NON_INTERACTIVE="1")
    missing = fixture("installer-missing-version", False, "all")
    missing_run = run(["bash", "install-offline.sh"], cwd=missing,
                      env={**env_base, "MOCK_IMAGE_POLICY": "all"})
    ok &= report("离线安装缺 APP_VERSION 返回 exit 2", missing_run.returncode == 2
                 and "APP_VERSION" in missing_run.stderr, f"exit={missing_run.returncode}")

    mismatch = fixture("installer-missing-image", True, "missing-frontend")
    mismatch_run = run(["bash", "install-offline.sh"], cwd=mismatch,
                       env={**env_base, "MOCK_IMAGE_POLICY": "missing-frontend"})
    mismatch_log = docker_log.read_text(encoding="utf-8")
    ok &= report("离线安装缺精确 tag 镜像时启动前失败", mismatch_run.returncode == 2
                 and "dbcool-frontend" in mismatch_run.stderr
                 and "up -d" not in mismatch_log, f"exit={mismatch_run.returncode}")

    matching = fixture("installer-matching-images", True, "all")
    docker_log.write_text("", encoding="utf-8")
    matching_run = run(["bash", "install-offline.sh"], cwd=matching,
                       env={**env_base, "MOCK_IMAGE_POLICY": "all"})
    matching_log = docker_log.read_text(encoding="utf-8")
    expected_images = [f"ghcr.io/whoamiwo/dbcool/dbcool-{service}:{RELEASE_TAG}" for service in APP_SERVICES]
    ok &= report("离线安装 tag 与本地四个应用镜像一致后才启动",
                 matching_run.returncode == 0 and all(image in matching_log for image in expected_images)
                 and "up -d --no-build" in matching_log,
                 f"exit={matching_run.returncode}; images={[image in matching_log for image in expected_images]}; "
                 f"up={'up -d --no-build' in matching_log}; stderr={matching_run.stderr.strip()}; "
                 f"log={matching_log.strip()}")
    return bool(ok)


def check_deploy_scripts(tmp: Path) -> bool:
    ok = True
    fixture = tmp / "deploy-fixture"
    (fixture / "scripts").mkdir(parents=True)
    for rel in ("deploy.sh", "docker-compose.yml", "docker-compose.observability.yml"):
        shutil.copyfile(ROOT / rel, fixture / rel)
    bin_dir = tmp / "deploy-bin"
    docker_log = tmp / "deploy-docker.log"
    fake_docker_bin(bin_dir, docker_log)
    env = clean_env(tmp, PATH=f"{bin_dir}:{os.environ.get('PATH', '/usr/bin:/bin')}",
                    MOCK_DOCKER_LOG=str(docker_log), DEPLOY_LOG_FILE=str(tmp / "deploy.log"))
    required = (
        "POSTGRES_PASSWORD=long-strong-postgres-password",
        "MINIO_ROOT_PASSWORD=long-strong-minio-password",
        "RABBITMQ_USER=nocobase",
        "RABBITMQ_PASSWORD=long-strong-rabbit-password",
        "INTEGRATION_CREDENTIAL_MASTER_KEY=local-test-master-key",
        "FRONTEND_PORT=18080",
    )
    (fixture / ".env").write_text("\n".join(required) + "\n", encoding="utf-8")
    missing = run(["bash", "deploy.sh", "prod"], cwd=fixture, env=env)
    calls = docker_log.read_text(encoding="utf-8")
    ok &= report("deploy prod 缺 APP_VERSION 在 Docker 写操作前失败",
                 missing.returncode == 2 and "compose pull" not in calls and "compose up" not in calls,
                 f"exit={missing.returncode}")

    def run_prod(tag: str) -> subprocess.CompletedProcess[str]:
        (fixture / ".env").write_text("APP_VERSION=" + tag + "\n" + "\n".join(required) + "\n", encoding="utf-8")
        docker_log.write_text("", encoding="utf-8")
        return run(["bash", "deploy.sh", "prod"], cwd=fixture,
                   env={**env, "MOCK_DOCKER_LOG": str(docker_log), "APP_VERSION": ""})

    first = run_prod("2.1.5-2")
    first_calls = docker_log.read_text(encoding="utf-8")
    first_ops = [line for line in first_calls.splitlines() if line.startswith("compose ")]
    first_ok = (first.returncode == 0 and (fixture / ".last-deployed-tag").read_text().strip() == "2.1.5-2"
                and any("compose pull" in line for line in first_ops)
                and any("compose up -d --no-build" in line for line in first_ops)
                and not any("compose up -d --build" in line for line in first_ops))
    ok &= report("deploy prod 使用 pull + up --no-build 并记录 APP_VERSION", first_ok,
                 f"exit={first.returncode}")

    second = run_prod(RELEASE_TAG)
    previous = (fixture / ".prev-deployed-tag").read_text(encoding="utf-8").strip() if (fixture / ".prev-deployed-tag").exists() else ""
    ok &= report("deploy prod 保存上一版镜像 tag", second.returncode == 0 and previous == "2.1.5-2",
                 f"exit={second.returncode}; previous={previous}")

    docker_log.write_text("", encoding="utf-8")
    rollback = run(["bash", "deploy.sh", "rollback"], cwd=fixture,
                   env={**env, "MOCK_DOCKER_LOG": str(docker_log), "APP_VERSION": ""})
    rollback_calls = docker_log.read_text(encoding="utf-8")
    rollback_ok = (rollback.returncode == 0
                   and (fixture / ".last-deployed-tag").read_text(encoding="utf-8").strip() == "2.1.5-2"
                   and (fixture / ".env").read_text(encoding="utf-8").startswith("APP_VERSION=2.1.5-2\n")
                   and any("compose pull" in line and "APP_VERSION=2.1.5-2" in line for line in rollback_calls.splitlines())
                   and any("compose up -d --no-build" in line and "APP_VERSION=2.1.5-2" in line
                           for line in rollback_calls.splitlines()))
    ok &= report("deploy rollback 恢复上一版 APP_VERSION + --no-build", rollback_ok,
                 f"exit={rollback.returncode}")

    # upgrade.sh：目标 tag 通过 APP_VERSION 传入，失败时读取 --from 回滚，成功/回滚均同步 .env。
    upgrade_fixture = tmp / "upgrade-fixture"
    (upgrade_fixture / "scripts").mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/upgrade.sh", upgrade_fixture / "scripts/upgrade.sh")
    shutil.copyfile(ROOT / "docker-compose.yml", upgrade_fixture / "docker-compose.yml")
    (upgrade_fixture / ".env").write_text("APP_VERSION=2.1.5-2\n", encoding="utf-8")
    docker_log.write_text("", encoding="utf-8")
    failed_upgrade = run(
        ["bash", "scripts/upgrade.sh", "--from", "2.1.5-2", "--to", RELEASE_TAG, "--skip-backup"],
        cwd=upgrade_fixture,
        env={**env, "MOCK_DOCKER_LOG": str(docker_log), "MOCK_HEALTH": "unhealthy",
             "UPGRADE_HEALTH_TIMEOUT_SECONDS": "0"},
    )
    failed_calls = docker_log.read_text(encoding="utf-8")
    failure_ok = (failed_upgrade.returncode == 1
                  and (upgrade_fixture / ".last-deployed-tag").read_text(encoding="utf-8").strip() == "2.1.5-2"
                  and (upgrade_fixture / ".env").read_text(encoding="utf-8").strip() == "APP_VERSION=2.1.5-2"
                  and any("pull backend-java backend-python crdt-service frontend" in line
                          and "APP_VERSION=2.1.5-3" in line for line in failed_calls.splitlines())
                  and any("up -d --no-build backend-java backend-python crdt-service frontend" in line
                          and "APP_VERSION=2.1.5-3" in line for line in failed_calls.splitlines())
                  and any("pull backend-java backend-python crdt-service frontend" in line
                          and "APP_VERSION=2.1.5-2" in line for line in failed_calls.splitlines())
                  and any("up -d --no-build backend-java backend-python crdt-service frontend" in line
                          and "APP_VERSION=2.1.5-2" in line for line in failed_calls.splitlines()))
    ok &= report("upgrade 失败时以 --from APP_VERSION + --no-build 回滚", failure_ok,
                 f"exit={failed_upgrade.returncode}; stderr={failed_upgrade.stderr.strip()}")

    # Blue-green CLI 控制流用 fake Docker，不会连接 daemon 或启动服务。
    bg_fixture = tmp / "blue-green-fixture"
    (bg_fixture / "scripts").mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/blue-green-deploy.sh", bg_fixture / "scripts/blue-green-deploy.sh")
    shutil.copyfile(ROOT / "docker-compose.yml", bg_fixture / "docker-compose.yml")
    shutil.copyfile(ROOT / "docker-compose.blue-green.yml", bg_fixture / "docker-compose.blue-green.yml")
    write_test_env(bg_fixture / ".env")
    no_to = run(["bash", "scripts/blue-green-deploy.sh"], cwd=bg_fixture, env=env)
    ok &= report("blue-green 未传 --to 返回失败", no_to.returncode == 2)
    docker_log.write_text("", encoding="utf-8")
    dry_run = run(["bash", "scripts/blue-green-deploy.sh", "--dry-run", "--to", RELEASE_TAG],
                  cwd=bg_fixture, env={**env, "MOCK_DOCKER_LOG": str(docker_log)})
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
        print("\n=== Offline installer APP_VERSION / local images ===")
        installer_ok = check_offline_installer(tmp)
        print("\n=== Production deploy / rollback / blue-green controls ===")
        deploy_ok = check_deploy_scripts(tmp)
    if compose_ok and package_ok and installer_ok and deploy_ok:
        print("\n✅ compose-version-selfcheck 全部通过")
        return 0
    print("\n❌ compose-version-selfcheck 存在失败项")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
