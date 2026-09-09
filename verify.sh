#!/bin/bash
# ============================================================
#  NocoBase 一键验证脚本
#  假设 install.sh java 已跑通,验证 Week 3/4/5/7/8 关键 API
#  用法:
#    chmod +x verify.sh
#    ./verify.sh             # 跑全套
#    ./verify.sh health      # 只健康检查
#    ./verify.sh auth        # 只认证
#    ./verify.sh collection  # 只 collection
#    ./verify.sh form        # 只 form(Week 8)
# ============================================================

set -e

CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'
BOLD='\033[1m'

JAVA_URL="http://localhost:8080"
PYTHON_URL="http://localhost:8000"
USERNAME="admin"
PASSWORD="admin123"

PASS=0
FAIL=0
RESULTS=()

section() { echo -e "\n${CYAN}${BOLD}=== $* ===${RESET}"; }
ok()      { PASS=$((PASS+1)); RESULTS+=("✅ $1"); echo -e "${GREEN}✅ $1${RESET}"; }
fail()    { FAIL=$((FAIL+1)); RESULTS+=("❌ $1"); echo -e "${RED}❌ $1${RESET}"; echo -e "${RED}   ${2:-}${RESET}"; }
info()    { echo -e "${YELLOW}ℹ️  $*${RESET}"; }
hr()      { echo "------------------------------------------------------------"; }

check_health() {
    section "1. 健康检查"
    if curl -sf -m 3 "$JAVA_URL/api/health" &>/dev/null; then
        ok "Java /api/health 通"
    else
        fail "Java /api/health 不通" "检查 ./install.sh status 或 tail -f /tmp/nocobase-java.log"
        return 1
    fi
    if curl -sf -m 3 "$PYTHON_URL/api/health" &>/dev/null; then
        ok "Python /api/health 通"
    else
        info "Python /api/health 不通(本周没改 Python,跳过)"
    fi
    return 0
}

check_auth() {
    section "2. 认证 API(Week 4 JWT)"
    LOGIN_RES=$(curl -s -m 5 -X POST "$JAVA_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" 2>/dev/null) || LOGIN_RES=""
    if [ -z "$LOGIN_RES" ]; then
        fail "登录失败" "curl $JAVA_URL/api/auth/login 看返回"
        return 1
    fi
    TOKEN=$(echo "$LOGIN_RES" | grep -o '"access_token":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -z "$TOKEN" ]; then
        fail "登录返回里没找到 access_token" "原始:$LOGIN_RES"
        return 1
    fi
    ok "登录成功,token 长度=${#TOKEN}"

    BAD_LOGIN=$(curl -s -m 5 -X POST "$JAVA_URL/api/auth/login" \
        -H "Content-Type: application/json" -d '{"username":"admin","password":"wrong"}')
    if echo "$BAD_LOGIN" | grep -q '"code":1001\|Unauthorized\|401'; then
        ok "错误密码被拒绝"
    else
        fail "错误密码没被拒绝" "返回:$BAD_LOGIN"
    fi

    ME_RES=$(curl -s -m 5 "$JAVA_URL/api/users/me" -H "Authorization: Bearer $TOKEN")
    if echo "$ME_RES" | grep -q '"username"'; then
        ok "Bearer token 鉴权通过"
    else
        fail "Bearer token 鉴权失败" "返回:$ME_RES"
    fi

    NO_AUTH=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$JAVA_URL/api/users/me")
    if [ "$NO_AUTH" = "401" ] || [ "$NO_AUTH" = "403" ]; then
        ok "无 token 被拒绝(HTTP $NO_AUTH)"
    else
        fail "无 token 应返回 401/403,实际 $NO_AUTH"
    fi
}

get_token() {
    curl -s -X POST "$JAVA_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" \
        | grep -o '"access_token":"[^"]*"' | head -1 | cut -d'"' -f4
}

check_collection() {
    section "3. Collection API(Week 5 + 7)"
    TOKEN=$(get_token)
    if [ -z "$TOKEN" ]; then
        fail "无法拿 token,跳过"
        return
    fi

    LIST_RES=$(curl -s -m 5 "$JAVA_URL/api/collections" -H "Authorization: Bearer $TOKEN")
    if echo "$LIST_RES" | grep -q '"code":0'; then
        ok "GET /api/collections 通"
    else
        fail "GET /api/collections 失败" "$LIST_RES"
    fi

    TS=$(date +%s)
    CREATE_RES=$(curl -s -m 5 -X POST "$JAVA_URL/api/collections" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"verify_${TS}\",\"title\":\"验证表\",\"fields\":[{\"name\":\"name\",\"type\":\"text\",\"required\":true}]}")
    if echo "$CREATE_RES" | grep -q '"code":0'; then
        ok "POST /api/collections 创建成功"
    else
        fail "POST /api/collections 失败" "$CREATE_RES"
        return
    fi
    COL_NAME="verify_${TS}"

    ADD_FIELD_RES=$(curl -s -m 5 -X POST "$JAVA_URL/api/collections/$COL_NAME/fields" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"name":"email","type":"text","required":false}')
    if echo "$ADD_FIELD_RES" | grep -q '"code":0'; then
        ok "POST /api/collections/{name}/fields 添加字段(Week 7 US-005)"
    else
        fail "添加字段失败" "$ADD_FIELD_RES"
    fi

    RENAME_RES=$(curl -s -m 5 -X PUT "$JAVA_URL/api/collections/$COL_NAME/fields/email" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"newName\":\"email_${TS}\"}")
    if echo "$RENAME_RES" | grep -q '"code":0'; then
        ok "PUT 重命名字段(Week 7 US-005)"
    else
        fail "重命名失败" "$RENAME_RES"
    fi

    REC_RES=$(curl -s -m 5 -X POST "$JAVA_URL/api/collections/$COL_NAME/records" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"张三\",\"email_${TS}\":\"zhang@example.com\"}")
    if echo "$REC_RES" | grep -q '"code":0'; then
        ok "POST 插记录(Week 5)"
    else
        fail "插记录失败" "$REC_RES"
    fi

    LIST_REC=$(curl -s -m 5 "$JAVA_URL/api/collections/$COL_NAME/records" -H "Authorization: Bearer $TOKEN")
    if echo "$LIST_REC" | grep -q '张三'; then
        ok "GET 列记录"
    else
        fail "列记录失败" "$LIST_REC"
    fi

    DEL_FIELD_RES=$(curl -s -m 5 -X DELETE "$JAVA_URL/api/collections/$COL_NAME/fields/email_${TS}" \
        -H "Authorization: Bearer $TOKEN")
    if echo "$DEL_FIELD_RES" | grep -q '"code":0'; then
        ok "DELETE 删除字段(Week 7 US-005)"
    fi

    curl -s -X DELETE "$JAVA_URL/api/collections/$COL_NAME" -H "Authorization: Bearer $TOKEN" &>/dev/null
}

check_form() {
    section "4. Form API(Week 8 Epic 2)"
    TOKEN=$(get_token)
    if [ -z "$TOKEN" ]; then
        fail "无法拿 token,跳过"
        return
    fi

    TS=$(date +%s)
    curl -s -X POST "$JAVA_URL/api/collections" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"formtest_${TS}\",\"title\":\"表单测试\",\"fields\":[{\"name\":\"name\",\"type\":\"text\"},{\"name\":\"age\",\"type\":\"number\"}]}" \
        > /dev/null
    COL_NAME="formtest_${TS}"

    CREATE_RES=$(curl -s -m 5 -X POST "$JAVA_URL/api/forms" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"collectionName\":\"$COL_NAME\",\"title\":\"客户登记表\",\"layout\":\"[{\\\"field\\\":\\\"name\\\",\\\"span\\\":24},{\\\"field\\\":\\\"age\\\",\\\"span\\\":12}]\",\"rules\":\"{\\\"validation\\\":{\\\"name\\\":[{\\\"type\\\":\\\"required\\\"}]}}\"}")
    if echo "$CREATE_RES" | grep -q '"code":0'; then
        ok "POST /api/forms 创建表单"
        FORM_ID=$(echo "$CREATE_RES" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    else
        fail "创建表单失败" "$CREATE_RES"
        return
    fi

    LIST_RES=$(curl -s -m 5 "$JAVA_URL/api/forms?collection=$COL_NAME" -H "Authorization: Bearer $TOKEN")
    if echo "$LIST_RES" | grep -q '客户登记表'; then
        ok "GET /api/forms?collection=xxx 列出"
    else
        fail "列出失败" "$LIST_RES"
    fi

    GET_RES=$(curl -s -m 5 "$JAVA_URL/api/forms/$FORM_ID" -H "Authorization: Bearer $TOKEN")
    if echo "$GET_RES" | grep -q '"layout":'; then
        ok "GET /api/forms/{id} 返回 layout 解析"
    else
        fail "获取详情失败" "$GET_RES"
    fi

    DEL_RES=$(curl -s -m 5 -X DELETE "$JAVA_URL/api/forms/$FORM_ID" -H "Authorization: Bearer $TOKEN")
    if echo "$DEL_RES" | grep -q '"code":0'; then
        ok "DELETE /api/forms/{id}"
    fi

    curl -s -X DELETE "$JAVA_URL/api/collections/$COL_NAME" -H "Authorization: Bearer $TOKEN" &>/dev/null
}

check_python_ai() {
    section "5. Python AI(Week 4 JWT)"
    if ! curl -sf -m 3 "$PYTHON_URL/api/health" &>/dev/null; then
        info "Python 没起,跳过"
        return
    fi
    TOKEN=$(get_token)
    if [ -z "$TOKEN" ]; then
        fail "无法拿 token"
        return
    fi
    ECHO_RES=$(curl -s -m 5 "$PYTHON_URL/api/ai/echo?msg=verify" -H "Authorization: Bearer $TOKEN")
    if echo "$ECHO_RES" | grep -q '"echo":"verify"'; then
        ok "Python /api/ai/echo JWT 校验通过"
    else
        fail "/api/ai/echo 失败" "$ECHO_RES"
    fi
    NO_AUTH=$(curl -s -m 5 "$PYTHON_URL/api/ai/echo?msg=test")
    if echo "$NO_AUTH" | grep -q '"code":1001\|detail.*Authorization'; then
        ok "Python 无 token 被拒绝"
    fi
}

summary() {
    section "总结"
    hr
    echo -e "  ${GREEN}通过:${PASS}${RESET}    ${RED}失败:${FAIL}${RESET}"
    hr
    if [ $FAIL -gt 0 ]; then
        echo ""
        echo -e "${RED}${BOLD}失败项:${RESET}"
        for r in "${RESULTS[@]}"; do
            [[ $r == ❌* ]] && echo "  $r"
        done
        echo ""
        echo -e "${YELLOW}排错:${RESET}"
        echo "  tail -50 /tmp/nocobase-java.log"
        echo "  ./install.sh status"
        echo "  把失败项贴给 Cline"
        exit 1
    else
        echo ""
        echo -e "${GREEN}${BOLD}🎉 全部通过!Week 3~8 API 验证完成。${RESET}"
        echo "下一步:./install.sh frontend 起前端体验 FormDesigner"
    fi
}

case "${1:-all}" in
    health) check_health; summary ;;
    auth) check_auth; summary ;;
    collection) check_collection; summary ;;
    form) check_form; summary ;;
    all)
        check_health || exit 1
        check_auth
        check_collection
        check_form
        check_python_ai
        summary
        ;;
    *)
        echo "用法:$0 {all|health|auth|collection|form}"
        exit 1
        ;;
esac
