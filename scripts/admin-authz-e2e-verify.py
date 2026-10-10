#!/usr/bin/env python3
"""
PHASE95 R3: Admin Authorization E2E Validation

Validates that @PreAuthorize annotations are actually being enforced.
For each protected endpoint:
  - Admin token → expect 200/201 (success or expected server-side error)
  - No token    → expect 401
  - User token  → expect 403 (MUST differ from admin → proves interception)

Must fail fast (exit 1) if user account cannot be created or logged in —
no None fallback that silently counts as PASS.

Usage: python3 scripts/admin-authz-e2e-verify.py
"""
import json
import os
import sys
import urllib.request
import urllib.error
import time

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = 10


def login(username, password):
    data = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(
        f"{BASE}/api/auth/login", data=data,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read())
            return body.get("data", {}).get("access_token"), \
                   body.get("data", {}).get("user", {}).get("roles", [])
    except urllib.error.HTTPError as e:
        return None, []


def http(method, path, token=None, data=None):
    url = f"{BASE}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method=method)
    if data is not None:
        req.data = json.dumps(data).encode()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            resp.read()
            return resp.status
    except urllib.error.HTTPError as e:
        e.read()
        return e.code
    except Exception:
        return -1


def fetch_json(path, token=None):
    """Return parsed JSON body; raises on error."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{BASE}{path}", headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read())


def get_user_id(admin_token, username):
    """Look up the UUID for a given username from the user list."""
    data = fetch_json("/api/admin/users", admin_token)
    for u in data.get("data", []):
        if u.get("username") == username:
            return u.get("id")
    return None


def main():
    print("=" * 70)
    print("PHASE95 R3: Admin Authorization E2E Validation")
    print(f"Base: {BASE}")
    print("=" * 70)

    # Login as admin
    admin_token, admin_roles = login("admin", "admin123")
    if not admin_token:
        print("FATAL: admin login failed")
        sys.exit(1)
    print(f"Admin roles: {admin_roles}")

    # Create a non-admin user for testing
    ts = int(time.time()) % 10000
    test_username = f"test_authz_{ts}"
    test_password = "testpass123"
    create_data = {
        "username": test_username,
        "password": test_password,
        "displayName": "Test Authz User"
    }
    create_status = http("POST", "/api/admin/users", admin_token, create_data)
    if create_status not in (200, 201):
        print(f"FATAL: Could not create test user (status={create_status})")
        print("Cannot verify 403 responses without a regular user")
        sys.exit(1)

    # Fetch the real user ID so we can use it in subsequent calls
    user_uuid = get_user_id(admin_token, test_username)
    if not user_uuid:
        print("FATAL: Could not find test user after creation")
        sys.exit(1)
    print(f"Test user UUID: {user_uuid}")

    user_token, user_roles = login(test_username, test_password)
    if not user_token:
        print(f"\nFATAL: Failed to login as new user '{test_username}'")
        sys.exit(1)
    print(f"Test user roles: {user_roles}")

    # Cleanup function (called at exit)
    import atexit
    def cleanup():
        try:
            http("DELETE", f"/api/admin/users/{user_uuid}", admin_token)
        except Exception:
            pass
    atexit.register(cleanup)

    # (method, path_template, label, valid_admin_status, valid_user_status, request_body_or_None)
    #   - path_template may contain {userId} placeholder — will be filled below.
    #   - valid_admin_status: acceptable admin response (200/201/400/404 — 400/404 means
    #     the endpoint itself is valid but input is bad/missing)
    #   - valid_user_status: MUST be [403] — no exceptions; proves @PreAuthorize blocked it
    #   - request_body_or_None: POST/PATCH/PUT body to send (or None for no-body GET/DELETE)
    #
    # The requirement: admin and user status codes MUST differ. Same code = unproven.

    TESTS = [
        # UserAdminController — uses real test user UUID
        ("GET",    "/api/admin/users",                              "list users",
         [200, 404, 405], [403], None),
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001",  "get user by id (nonexistent)",
         [200, 404],      [403], None),
        ("PATCH",  "/api/admin/users/{userId}",                    "update user",
         [200],           [403], {"displayName": "authz-test"}),
        ("DELETE", "/api/admin/users/deadbeef-dead-beef-dead-deadbeef0000", "delete user (nonexistent)",
         [200, 404, 400], [403], None),
        # assign role — valid role ID (we find an existing role from the DB)
        ("POST",   "/api/admin/users/{userId}/roles/{roleId}",     "assign role (valid role)",
         [200, 404],      [403], None),  # roleId injected below; admin succeeds, user blocked
        # assign role — invalid/fake role ID → admin should get 400 (or 404), NOT 500
        ("POST",   "/api/admin/users/{userId}/roles/00000000-0000-0000-0000-000000000099",
         "assign role (invalid role)",
         [400, 404],      [403], None),
        # reset password — needs valid password field
        ("POST",   "/api/admin/users/{userId}/password",           "reset user password",
         [200],           [403], {"password": "newpass123!"}),
        # JwtKeyRotationController
        ("GET",    "/api/admin/jwt-keys",                           "jwt keys snapshot",
         [200],           [403], None),
        ("POST",   "/api/admin/jwt-keys/rotate",                    "rotate jwt key",
         [200, 429],      [403], None),
        # TenantQuotaController — self-service quota is authenticated but not admin-only
        # Design: any authenticated user can check their own quota (user=200 is correct)
        # This endpoint uses @PreAuthorize("isAuthenticated()") — not ADMIN-specific.
        # The admin-level quota endpoint is /api/admin/tenants/{id}/quota (hasRole('ADMIN')).
        ("GET",    "/api/tenant/quota",                             "get my quota (self-service)",
         [200],           [200], None),  # Authenticated self-service — same status for admin/user is by design
        # AuditController
        ("GET",    "/api/audit/logs?limit=5",                       "audit logs",
         [200],           [403], None),
        # UserDataComplianceController
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001/data-export",
         "compliance check",
         [200, 404],      [403], None),
        # UserTenantController
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001/tenants",
         "user tenants",
         [200, 404],      [403], None),
        # TenantController
        ("GET",    "/api/admin/tenants",                            "list tenants",
         [200],           [403], None),
        ("GET",    "/api/admin/tenants/00000000-0000-0000-0000-000000000001",
         "get tenant by id",
         [200, 404],      [403], None),
        # AutomationRuleController — valid body required
        ("GET",    "/api/automation",                               "list automation rules",
         [200],           [403], None),
        ("POST",   "/api/automation",                               "create automation rule",
         [201],           [403], {
             "name": "authz-test-rule",
             "description": "test",
             "collectionName": "users",
             "triggerType": "manual",
             "triggerConfig": {},
             "conditions": [],
             "actions": []
         }),
        # NotificationChannelController
        ("GET",    "/api/admin/notification-channels",              "list notification channels",
         [200],           [403], None),
    ]

    # Inject the real test user UUID into templates that need it
    enriched_tests = []
    for item in TESTS:
        if len(item) == 6:
            method, path_tpl, label, valid_admin, valid_user, body = item
            # find a valid role ID from the DB for assign-role test
            if "assign role" in label and "{roleId}" in path_tpl:
                # list roles first to find an existing one
                try:
                    role_data = fetch_json("/api/admin/roles", admin_token)
                    roles = role_data.get("data", [])
                    if roles:
                        test_role_id = roles[0]["id"]
                    else:
                        test_role_id = "00000000-0000-0000-0000-000000000002"
                except Exception:
                    test_role_id = "00000000-0000-0000-0000-000000000002"
                path = path_tpl.format(userId=user_uuid, roleId=test_role_id)
            else:
                path = path_tpl.format(userId=user_uuid)
            enriched_tests.append((method, path, label, valid_admin, valid_user, body))
        else:
            enriched_tests.append(item)
    TESTS = enriched_tests

    results = []
    for method, path, label, valid_admin, valid_user, body in TESTS:
        print(f"\n  {label} [{method} {path}]")

        # No token → expect 401
        no_auth_status = http(method, path, None)
        no_auth_ok = no_auth_status == 401
        print(f"    {'✅' if no_auth_ok else '❌'} no-auth → {no_auth_status} (want 401)")

        # Admin token
        admin_status = http(method, path, admin_token, body)
        admin_ok = admin_status in valid_admin
        print(f"    {'✅' if admin_ok else '❌'} admin   → {admin_status} (want one of {valid_admin})")

        # User token
        # For self-service endpoints (e.g. /api/tenant/quota) where valid_user == [200],
        # admin and user are BOTH expected to get 200 — that is correct by design
        # (any authenticated user can check their own quota).  In that case we simply
        # require user_status to be in valid_user and do NOT flag "same as admin".
        user_status = http(method, path, user_token, body)
        user_ok = user_status in valid_user
        same_as_admin = (user_status == admin_status)
        # Only flag as "same as admin" when valid_user does NOT include the observed status
        # (i.e. the user should have been rejected but wasn't)
        flagged = same_as_admin and (user_status not in valid_user)
        diff_mark = " ⚠️  SAME as admin (unproven)" if flagged else ""
        expected_desc = "403" if 403 in valid_user else f"one of {valid_user}"
        print(f"    {'✅' if user_ok else '❌'} user    → {user_status} (want {expected_desc}){diff_mark}")

        passed = no_auth_ok and admin_ok and user_ok
        results.append((label, passed,
            f"no-auth={no_auth_status} admin={admin_status} user={user_status}"
            + ("  ← SAME (unproven)" if flagged else "")))

    print("\n" + "=" * 70)
    total = len(results)
    passed = sum(1 for _, p, _ in results if p)
    print(f"Results: {passed}/{total} PASS")
    for label, p, detail in results:
        mark = "✅" if p else "❌"
        print(f"  {mark} {label} — {detail}")

    if passed < total:
        print("\n⚠️  Some tests failed — authorization evidence is incomplete.")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
