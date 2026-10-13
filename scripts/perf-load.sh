#!/bin/bash
# PHASE103 T4: 性能测试脚本
# 用法：./scripts/perf-load.sh [--duration 60] [--threads 4] [--connections 16]
# 默认：10s / 2 threads / 8 connections

set -euo pipefail

DURATION=${DURATION:-10}
THREADS=${THREADS:-2}
CONNECTIONS=${CONNECTIONS:-8}
TARGET_URL=${TARGET_URL:-"http://localhost:8000/api/v1/workflow/trigger"}

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --duration) DURATION="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --connections) CONNECTIONS="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

echo "=== NocoBase 性能测试 ==="
echo "目标 URL: $TARGET_URL"
echo "持续时间: ${DURATION}s, 线程数: $THREADS, 连接数: $CONNECTIONS"
echo ""

# 检查 wrk 是否可用
if ! command -v wrk &> /dev/null; then
    echo "错误：wrk 未安装"
    echo "Ubuntu: sudo apt install wrk"
    echo "macOS: brew install wrk"
    exit 1
fi

# 检查服务是否健康
if ! curl -sf http://localhost:8000/health > /dev/null 2>&1; then
    echo "警告：后端服务未启动或未响应 /health"
    read -p "继续压测？(y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# 运行 wrk
echo "开始压测..."
wrk -t"$THREADS" -c"$CONNECTIONS" -d"${DURATION}s" \
    --latency \
    "$TARGET_URL"

echo ""
echo "提示：如需自定义请求体，修改 TARGET_URL 或使用 wrk 的 --script 参数"
