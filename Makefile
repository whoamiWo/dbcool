# ============================================================
#  NocoBase 三栈开发 — 统一命令入口
#  用法: make <target>
# ============================================================

.PHONY: help up up-infra up-all down logs restart clean

CYAN  := \033[36m
RESET := \033[0m

.DEFAULT_GOAL := help

help: ## 显示帮助
	@echo "$(CYAN)NocoBase 多栈开发命令$(RESET)"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-20s$(RESET) %s\n", $$1, $$2}'

# ============================================================
#  启动模式
# ============================================================

up-infra: ## 仅启动基础设施(Postgres + Redis + MinIO)
	docker compose up -d postgres redis minio
	@echo "✅ 基础设施已启动"

up-all: ## 启动全栈(基础设施 + 三栈 + Nginx)
	docker compose up -d --build
	@echo "✅ 全栈已启动"
	@echo "  前端(含反代): http://localhost:80"
	@echo "  Java 直连:     http://localhost:8080"
	@echo "  Python 直连:   http://localhost:8000"

down: ## 停止全栈
	docker compose down

logs: ## 查看全栈日志
	docker compose logs -f

restart: down up-all ## 重启全栈

clean: ## 清理所有数据卷(慎用!)
	docker compose down -v
	@echo "⚠️  所有数据已清除"

# ============================================================
#  本地开发(三栈独立启动,需先 make up-infra)
# ============================================================

java: ## Java 本地起
	cd backend-java && mvn spring-boot:run

python: ## Python 本地起
	cd backend-python && uv run uvicorn nocobase_py.main:app --reload --port 8000

frontend: ## React 本地起
	cd frontend && pnpm dev

# ============================================================
#  测试
# ============================================================

test: ## 三栈全部测试
	@echo "$(CYAN)▶ Java$(RESET)"
	cd backend-java && mvn test -q
	@echo "$(CYAN)▶ Python$(RESET)"
	cd backend-python && uv run pytest -q
	@echo "$(CYAN)▶ Frontend$(RESET)"
	cd frontend && pnpm test --run
	@echo "✅ 全部测试完成"

test-java: ## 仅 Java
	cd backend-java && mvn test

test-python: ## 仅 Python
	cd backend-python && uv run pytest

test-frontend: ## 仅前端
	cd frontend && pnpm test --run

test-contract: ## 仅契约测试
	cd tests/contract && uv run pytest -v

# ============================================================
#  构建
# ============================================================

build: build-java build-python build-frontend ## 三栈构建

build-java: ## Java 构建
	cd backend-java && mvn clean package -DskipTests

build-python: ## Python 构建
	cd backend-python && uv build

build-frontend: ## 前端构建
	cd frontend && pnpm build

# ============================================================
#  Lint / 格式化
# ============================================================

lint: ## 三栈 lint
	@echo "$(CYAN)▶ Java Checkstyle$(RESET)"
	cd backend-java && mvn checkstyle:check -q || true
	@echo "$(CYAN)▶ Python$(RESET)"
	cd backend-python && uv run ruff check . && uv run mypy .
	@echo "$(CYAN)▶ Frontend$(RESET)"
	cd frontend && pnpm lint && pnpm tsc --noEmit

format: ## 三栈格式化
	cd backend-java && mvn spotless:apply || true
	cd backend-python && uv run ruff format .
	cd frontend && pnpm format

# ============================================================
#  依赖安装
# ============================================================

install: ## 安装三栈依赖
	@echo "$(CYAN)▶ Java$(RESET)"
	cd backend-java && mvn dependency:resolve
	@echo "$(CYAN)▶ Python$(RESET)"
	cd backend-python && uv sync --all-extras
	@echo "$(CYAN)▶ Frontend$(RESET)"
	cd frontend && pnpm install
	@echo "✅ 三栈依赖安装完成"
