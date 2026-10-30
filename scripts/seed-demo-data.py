#!/usr/bin/env python3
"""
Wave2 T2.4 — 演示数据种子（**默认关闭**）

用途：为产品展示/试用环境预置一套可直接观看的演示数据：
  - 1 个集合 demo_contacts（客户联系人：姓名/公司/电话/邮箱/状态/金额）
  - 12 条记录（覆盖不同状态，便于看板/日历/表格演示）
  - 1 个看板视图（按 status 分组）
  - 1 条工作流（记录创建时发送通知）

安全红线（红线条目 4）：
  演示数据不得污染生产 —— 必须显式开启：
      python3 scripts/seed-demo-data.py --seed-demo
  或设置环境变量 DEMO_SEED=true。
  未显式开启时**不写入任何数据**，仅打印将要执行的动作并 exit 0。

退出码：0 = 成功（含"未启用"空跑）；1 = 执行失败；2 = 参数/环境错误
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("DBCOOL_BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("DBCOOL_USERNAME", "admin")
PASSWORD = os.environ.get("DBCOOL_PASSWORD", "admin123")

COLLECTION = "demo_contacts"
# FieldDef 契约：{name, type, required, label, options}；select 的 options = {"options": [值数组]}
FIELDS = [
    {"name": "name", "type": "text", "required": True, "label": "姓名"},
    {"name": "company", "type": "text", "required": False, "label": "公司"},
    {"name": "phone", "type": "phone", "required": False, "label": "电话"},
    {"name": "email", "type": "email", "required": False, "label": "邮箱"},
    {"name": "status", "type": "select", "required": False, "label": "状态",
     "options": {"options": ["lead", "contact", "opportunity", "won"]}},
    {"name": "amount", "type": "currency", "required": False, "label": "金额"},
]

RECORDS = [
    ("张明", "云启科技", "13800000001", "zhangming@example.com", "lead", 0),
    ("李芳", "海川贸易", "13800000002", "lifang@example.com", "lead", 0),
    ("王强", "恒通物流", "13800000003", "wangqiang@example.com", "contact", 0),
    ("刘洋", "智造工厂", "13800000004", "liuyang@example.com", "contact", 0),
    ("陈静", "绿源农业", "13800000005", "chenjing@example.com", "opportunity", 68000),
    ("赵磊", "蓝海网络", "13800000006", "zhaolei@example.com", "opportunity", 125000),
    ("孙悦", "天成建筑", "13800000007", "sunyue@example.com", "opportunity", 32000),
    ("周涛", "华兴材料", "13800000008", "zhoutao@example.com", "won", 260000),
    ("吴敏", "新光零售", "13800000009", "wumin@example.com", "won", 98000),
    ("郑凯", "远航能源", "13800000010", "zhengkai@example.com", "won", 410000),
    ("冯雪", "星辰教育", "13800000011", "fengxue@example.com", "contact", 0),
    ("何军", "宏图咨询", "13800000012", "hejun@example.com", "lead", 0),
]


def request(method: str, path: str, token: str = None, body=None):
    url = f"{BASE_URL}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="ignore")
        return e.code, {"raw": raw[:300]}
    except Exception as e:  # noqa: BLE001
        return 0, {"error": str(e)}


def login():
    status, resp = request("POST", "/api/auth/login", body={"username": USERNAME, "password": PASSWORD})
    if status != 200:
        print(f"❌ 登录失败（HTTP {status}）：{resp}", file=sys.stderr)
        sys.exit(2)
    data = resp.get("data") or resp
    token = data.get("access_token") or data.get("token")
    if not token:
        print(f"❌ 登录响应中无 token：{resp}", file=sys.stderr)
        sys.exit(2)
    return token


def main():
    ap = argparse.ArgumentParser(description="演示数据种子（默认关闭，需显式开启）")
    ap.add_argument("--seed-demo", action="store_true", help="显式开启写入演示数据")
    ap.add_argument("--dry-run", action="store_true", help="只打印将执行的动作，不写入")
    args = ap.parse_args()

    enabled = args.seed_demo or os.environ.get("DEMO_SEED", "").lower() in ("1", "true", "yes")

    print("=" * 64)
    print("Wave2 T2.4 演示数据种子")
    print("=" * 64)
    print(f"目标：{BASE_URL}   集合：{COLLECTION}")
    print(f"计划：1 个集合 / {len(RECORDS)} 条记录 / 1 个看板视图 / 1 条工作流")

    if not enabled:
        print("\nℹ️  演示数据默认关闭（红线：不得污染生产）。")
        print("   需要写入时请显式开启其一：")
        print("     python3 scripts/seed-demo-data.py --seed-demo")
        print("     DEMO_SEED=true python3 scripts/seed-demo-data.py")
        print("\n未写入任何数据。exit 0。")
        return 0

    if args.dry_run:
        print("\n--dry-run：仅打印动作，不写入。")
        return 0

    token = login()
    print("\n✅ 登录成功")

    # 1) 集合（幂等：已存在则跳过）
    status, resp = request("GET", f"/api/collections/{COLLECTION}", token)
    if status == 200:
        print(f"ℹ️  集合 {COLLECTION} 已存在，跳过创建")
    else:
        status, resp = request("POST", "/api/collections", token, {
            "name": COLLECTION, "title": "演示客户联系人", "fields": FIELDS,
        })
        if status not in (200, 201):
            print(f"❌ 创建集合失败（HTTP {status}）：{resp}", file=sys.stderr)
            sys.exit(1)
        print(f"✅ 创建集合 {COLLECTION}")

    # 2) 记录（幂等：按 name 去重）
    existing = set()
    status, resp = request("GET", f"/api/collections/{COLLECTION}/records?limit=200", token)
    if status == 200:
        for r in (resp.get("data") or []):
            existing.add(r.get("name"))
    created = 0
    for name, company, phone, email, status_v, amount in RECORDS:
        if name in existing:
            continue
        st, rr = request("POST", f"/api/collections/{COLLECTION}/records", token, {
            "name": name, "company": company, "phone": phone, "email": email,
            "status": status_v, "amount": amount,
        })
        if st not in (200, 201):
            print(f"⚠️  记录 {name} 写入失败（HTTP {st}）：{rr}")
            continue
        created += 1
    print(f"✅ 记录：新增 {created} 条（跳过已存在 {len(RECORDS) - created} 条）")

    # 3) 看板视图（按 status 分组）
    status, resp = request("GET", f"/api/views?collection={COLLECTION}", token)
    has_kanban = status == 200 and any(
        v.get("type") == "kanban" and v.get("collection_name") == COLLECTION
        for v in (resp.get("data") or []))
    if has_kanban:
        print("ℹ️  看板视图已存在，跳过")
    else:
        st, rr = request("POST", "/api/views", token, {
            "collectionName": COLLECTION, "name": "demo_kanban", "title": "演示看板（按状态分组）",
            "type": "kanban", "config": json.dumps({"groupBy": "status"}),
        })
        if st in (200, 201):
            print("✅ 创建看板视图")
        else:
            print(f"⚠️  创建看板视图失败（HTTP {st}）：{rr}")

    # 4) 工作流（记录创建 → 通知）
    status, resp = request("GET", "/api/workflows?limit=100", token)
    has_wf = status == 200 and any(
        w.get("name") == "演示流程：新建联系人即通知" for w in (resp.get("data") or []))
    if has_wf:
        print("ℹ️  演示工作流已存在，跳过")
    else:
        st, rr = request("POST", "/api/workflows", token, {
            "name": "演示流程：新建联系人即通知",
            "title": "演示流程：新建联系人即通知",
            "description": "演示数据自带的轻量流程（展示工作流面板）",
            "collectionName": COLLECTION,
            "enabled": True,
            "trigger": json.dumps({"type": "on_create", "collectionName": COLLECTION}),
            "nodes": json.dumps([
                {"id": "n1", "type": "condition", "config": {"expression": "1 == 1"}},
                {"id": "n2", "type": "notification", "config": {"channel": "system", "title": "新建联系人",
                                                              "body": "演示流程已触发：新建联系人"}},
            ]),
            "edges": "[]",
        })
        if st in (200, 201):
            print("✅ 创建演示工作流（enabled=true；注意：仅在显式开启演示时创建）")
        else:
            print(f"⚠️  创建工作流失败（HTTP {st}）：{rr}")

    print("\n✅ 演示数据种子完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
