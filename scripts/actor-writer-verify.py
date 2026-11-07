#!/usr/bin/env python3
"""A0-6：归属列写入路径静态门禁。

发布阻断背景：一旦控制器/表单/自动化回退到不带 UUID actor 的旧重载，
记录会重新出现 `created_by IS NULL`，SELF/DEPT 数据范围将 fail-closed 地隐藏这些记录。

规则：
1. CollectionController 的 insert/update/batch/delete 调用必须在同一调用内包含
   `user.userId()`。
2. FormService / AutomationRuleService 的写入必须包含表单/规则创建者。
3. DynamicTableManager 的读取投影必须回传 `created_by`。

退出码：0 = PASS；1 = 发现旧重载调用。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend-java/src/main/java/com/nocobase"

CONTROLLER = BACKEND / "meta/CollectionController.java"
FORM = BACKEND / "form/FormService.java"
AUTOMATION = BACKEND / "automation/AutomationRuleService.java"
DYNAMIC = BACKEND / "meta/DynamicTableManager.java"

WRITE_METHODS = ("insertRecord", "updateRecord", "batchInsert", "batchUpdate",
                 "batchDelete", "deleteRecord")
WINDOW = 320


def read(path: Path) -> str:
    if not path.exists():
        print(f"FAIL: 文件不存在 {path}")
        sys.exit(1)
    return path.read_text()


def check_actor_calls(path: Path, expected: str) -> list[str]:
    text = read(path)
    errors = []
    pattern = re.compile(r"service\.(" + "|".join(WRITE_METHODS) + r")\s*\(")
    for m in pattern.finditer(text):
        chunk = text[m.start():m.start() + WINDOW]
        if expected not in chunk:
            line = text[:m.start()].count("\n") + 1
            errors.append(f"{path.name}:{line} 调用 {m.group(1)} 缺少 {expected}")
    return errors


def main() -> int:
    errors = []
    errors += check_actor_calls(CONTROLLER, "user.userId()")
    errors += check_actor_calls(FORM, "form.getCreatedBy()")
    errors += check_actor_calls(AUTOMATION, "rule.getCreatedBy()")

    dynamic = read(DYNAMIC)
    if "RECORD_PROJECTION" not in dynamic or "'created_by'" not in dynamic:
        errors.append("DynamicTableManager 读取投影缺少 created_by")

    if errors:
        print("❌ A0-6 归属写入静态门禁失败：")
        for e in errors:
            print("  - " + e)
        return 1
    print("✅ A0-6 归属写入静态门禁通过（控制器/表单/自动化均携带 actor）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
