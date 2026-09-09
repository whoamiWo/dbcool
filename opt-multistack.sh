#!/bin/bash
# ============================================================
#  opt-multistack.sh
#  一键启动 Java + Python + JS/TS 三栈 Prime daemon
#  用法:
#    chmod +x opt-multistack.sh
#    ./opt-multistack.sh start      # 启动四个 daemon
#    ./opt-multistack.sh stop       # 停止所有 daemon
#    ./opt-multistack.sh status     # 查看状态
#    ./opt-multistack.sh logs java  # 查看 java 栈日志
#    ./opt-multistack.sh clean      # 清理历史 session
# ============================================================

set -e

PROJECT_ROOT="/home/who/multistack-project"
DATE=$(date +%Y%m%d)
SESSION_BASE="/home/who/.prime/agent/sessions"
PID_DIR="/tmp/multistack-prime"

make_goal() {
cat <<EOF
你是 ${1} 栈的优化工程师。请按 /home/who/multistack-project/OPTIMIZATION_PLAN.md 中归属于 ${1} 栈的任务(编号 T-${1:0:1}*),逐项迭代优化。

# 工作纪律
1. ${2}
2. 严禁跨栈改动:不要碰其他语言栈的代码(避免破坏其他栈)
3. 跨栈影响必须上报:
   - 任何会影响 API 字段、错误码、协议、数据格式的改动
   - 写入 /home/who/multistack-project/CROSS_STACK_ISSUES.md
4. 每完成一个任务:
   - 运行验证命令: ${3}
   - 全部通过后才 git commit,格式: "[prime-${1}] T-<编号> <描述>"
   - 在 /home/who/multistack-project/CHANGELOG_AI.md 追加记录(标注 [${1}])
   - 更新 /home/who/multistack-project/OPTIMIZATION_PLAN.md 中该任务为 [x]
5. 开始新任务前,先读 /home/who/multistack-project/FEEDBACK.md
6. 遇到 OPTIMIZATION_PLAN.md 未覆盖的问题,先记录到 QUESTIONS.md,不要自行假设

# 终止条件(全部满足才停止)
- 本栈所有任务状态 = [x]
- ${4}
EOF
}

ensure_dirs() {
    mkdir -p "$PID_DIR"
    mkdir -p "$SESSION_BASE"
}

start_stack() {
    local stack=$1
    local goal=$2
    local pid_file="$PID_DIR/prime-$stack.pid"
    local session_dir="$SESSION_BASE/$stack-$DATE"

    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "⚠️  prime-$stack 已在运行 (PID: $(cat "$pid_file"))"
        return 0
    fi

    echo "🚀 启动 prime-$stack ..."
    prime-agent \
        --mode daemon \
        --cwd "$PROJECT_ROOT" \
        --goal "$goal" \
        --goal-token-budget 400000 \
        --models "minimax/MiniMax-M3,anthropic/claude-sonnet" \
        --session-dir "$session_dir" \
        --verbose > "$PID_DIR/prime-$stack.log" 2>&1 &

    echo $! > "$pid_file"
    sleep 2
    if kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "   ✅ 已启动 (PID: $(cat "$pid_file"), session: $session_dir)"
    else
        echo "   ❌ 启动失败,查看日志: $PID_DIR/prime-$stack.log"
        rm -f "$pid_file"
        return 1
    fi
}

start_all() {
    ensure_dirs

    start_stack "java" "$(make_goal java \
        '运行 mvn -q test(100% 通过)+ mvn -q checkstyle:check pmd:check spotbugs:check(0 错误)+ mvn -q jacoco:report(覆盖率 ≥ 70%)' \
        'mvn -q test && mvn -q checkstyle:check pmd:check' \
        'Java 测试 100% 通过 + CheckStyle/PMD 0 错误 + JaCoCo ≥ 70%')"

    start_stack "python" "$(make_goal python \
        '运行 pytest -q(100% 通过)+ ruff check .(0 错误)+ mypy .(0 错误)+ pytest --cov=. --cov-fail-under=80' \
        'pytest -q --tb=short && ruff check . && mypy .' \
        'pytest 100% 通过 + ruff/mypy 0 错误 + 覆盖率 ≥ 80%')"

    start_stack "js" "$(make_goal js \
        '运行 npm test(100% 通过)+ npm run lint(0 错误)+ npx tsc --noEmit(0 错误)+ 覆盖率 ≥ 75%' \
        'npm test && npm run lint && npx tsc --noEmit' \
        'npm test 100% 通过 + eslint/tsc 0 错误 + 覆盖率 ≥ 75%')"

    start_stack "contract" "$(cat <<'EOF'
你是三栈架构的契约监督员,只读不改。

# 任务
1. 监控 /home/who/multistack-project/CROSS_STACK_ISSUES.md
2. 对每条问题给出统一契约方案,写入 CONTRACT_DECISIONS.md
3. 每 30 分钟扫描 git log 识别影响契约的改动
4. 严格不直接修改 src/ 下的代码,只产出决策文档

# 关键原则
- 向后兼容优先
- 类型严格:Java 强类型、Python Pydantic、TS interface + zod
- 错误码统一
EOF
)"

    echo ""
    echo "============================================================"
    echo "✅ 全部 4 个 Prime daemon 已启动"
    echo "============================================================"
    echo "  prime-java     PID: $(cat "$PID_DIR/prime-java.pid" 2>/dev/null || echo 'N/A')"
    echo "  prime-python   PID: $(cat "$PID_DIR/prime-python.pid" 2>/dev/null || echo 'N/A')"
    echo "  prime-js       PID: $(cat "$PID_DIR/prime-js.pid" 2>/dev/null || echo 'N/A')"
    echo "  prime-contract PID: $(cat "$PID_DIR/prime-contract.pid" 2>/dev/null || echo 'N/A')"
    echo ""
    echo "📋 常用命令:"
    echo "  ./opt-multistack.sh status"
    echo "  ./opt-multistack.sh logs <stack>"
    echo "  ./opt-multistack.sh stop"
}

stop_all() {
    ensure_dirs
    echo "🛑 停止所有 Prime daemon ..."
    for stack in java python js contract; do
        pid_file="$PID_DIR/prime-$stack.pid"
        if [ -f "$pid_file" ]; then
            pid=$(cat "$pid_file")
            if kill -0 "$pid" 2>/dev/null; then
                kill "$pid" 2>/dev/null && echo "   ✅ 停止 prime-$stack (PID: $pid)" || echo "   ⚠️  prime-$stack 停止失败"
            else
                echo "   ⚠️  prime-$stack (PID: $pid) 已不运行"
            fi
            rm -f "$pid_file"
        else
            echo "   -  prime-$stack 未启动"
        fi
    done
}

status() {
    ensure_dirs
    echo "📊 Prime daemon 状态"
    echo "------------------------------------------------------------"
    printf "%-15s %-10s %-20s\n" "STACK" "STATUS" "PID"
    echo "------------------------------------------------------------"
    for stack in java python js contract; do
        pid_file="$PID_DIR/prime-$stack.pid"
        if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
            printf "%-15s %-10s %-20s\n" "prime-$stack" "RUNNING" "$(cat "$pid_file")"
        else
            printf "%-15s %-10s %-20s\n" "prime-$stack" "STOPPED" "-"
        fi
    done
    echo "------------------------------------------------------------"
    echo "📁 日志目录: $PID_DIR"
    echo "📁 Session 目录: $SESSION_BASE"
}

show_logs() {
    local stack=$1
    local log_file="$PID_DIR/prime-$stack.log"
    if [ -f "$log_file" ]; then
        tail -f "$log_file"
    else
        echo "❌ 日志文件不存在: $log_file"
        exit 1
    fi
}

clean() {
    echo "🧹 清理历史 session (保留最近 5 个)..."
    if [ -d "$SESSION_BASE" ]; then
        cd "$SESSION_BASE"
        ls -dt java-* python-* js-* contract-* 2>/dev/null | tail -n +6 | while read dir; do
            echo "   🗑️  删除 $dir"
            rm -rf "$dir"
        done
        cd - > /dev/null
    fi
    echo "✅ 清理完成"
}

case "${1:-}" in
    start)
        start_all
        ;;
    stop)
        stop_all
        ;;
    status)
        status
        ;;
    logs)
        if [ -z "${2:-}" ]; then
            echo "用法: $0 logs <java|python|js|contract>"
            exit 1
        fi
        show_logs "$2"
        ;;
    clean)
        clean
        ;;
    *)
        echo "用法: $0 {start|stop|status|logs <stack>|clean}"
        echo ""
        echo "  start   - 启动 4 个 Prime daemon"
        echo "  stop    - 停止所有 daemon"
        echo "  status  - 查看 daemon 状态"
        echo "  logs    - 实时跟踪指定栈的日志"
        echo "  clean   - 清理历史 session"
        exit 1
        ;;
esac

