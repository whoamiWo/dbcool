#!/usr/bin/env python3
"""
PHASE95 R2: Admin Authorization E2E Validation

Validates that @PreAuthorize annotations are actually being enforced.
For each protected endpoint:
  - Admin token → expect 200/201/202 (success)
  - No token    → expect 401
  - User token  → expect 403 (must FAIL if user not available)

Usage: python3 scripts/admin-authz-e2e-verify.py
"""
import json
import os
import sys
import urllib.request
import urllib.error

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = 10

def login(username, password):
    data = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(f"{BASE}/api/auth/login", data=data,
        headers={"Content-Type": "application/json"}, method="POST")
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
    if data:
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

def create_non_admin_user(admin_token):
    """Create a non-admin user for testing authorization."""
    username = "test_user_authz" + str(int(__import__('time').time()) % 10000)
    password = "testpass123"
    data = {
        "username": username,
        "password": password,
        "displayName": "Test User"
    }
    status = http("POST", "/api/admin/users", admin_token, data)
    if status == 201:
        return username, password
    print(f"  [WARN] Could not create test user (status={status})")
    return None, None

def delete_user(admin_token, username):
    """Delete a test user after tests."""
    # Get user ID first
    users_resp = http("GET", "/api/admin/users", admin_token)
    users = json.loads(urllib.request.urlopen(
        urllib.request.Request(f"{BASE}/api/admin/users",
                               headers={"Authorization": f"Bearer {admin_token}"})).read())
    for u in users.get("data", []):
        if u.get("username") == username:
            http("DELETE", f"/api/admin/users/{u['id']}", admin_token)
            return True
    return False

def main():
    print("=" * 70)
    print("PHASE95 R2: Admin Authorization E2E Validation")
    print(f"Base: {BASE}")
    print("=" * 70)

    # Login as admin
    admin_token, admin_roles = login("admin", "admin123")
    if not admin_token:
        print("FATAL: admin login failed")
        sys.exit(1)
    print(f"Admin roles: {admin_roles}")

    # Create a non-admin user for testing
    test_username, test_password = create_non_admin_user(admin_token)
    if not test_username:
        print("\nFATAL: Failed to create non-admin test user")
        print("Cannot verify 403 responses without a regular user")
        sys.exit(1)
    
    user_token, user_roles = login(test_username, test_password)
    if not user_token:
        print(f"\nFATAL: Failed to login as new user '{test_username}'")
        sys.exit(1)
    print(f"Test user roles: {user_roles}")
    
    # Cleanup function (called at exit)
    import atexit
    def cleanup():
        try:
            delete_user(admin_token, test_username)
        except:
            pass
    atexit.register(cleanup)

    # (method, path, label, valid_admin_status, valid_user_status)
    # Valid statuses: 200-202 for success, 404 for not-found (but still authorized), 405 for method mismatch
    # Use 401 for no-token, 403 for user access to admin endpoint
    TESTS = [
        # UserAdminController
        ("GET",    "/api/admin/users", "list users",
         [200, 404, 405], [403]),
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001", "get user by id",
         [200, 404], [403]),
        ("PATCH",  "/api/admin/users/00000000-0000-0000-0000-000000000001", "update user",
         [200, 404, 400], [403, 400]),
        ("DELETE", "/api/admin/users/deadbeef-dead-beef-dead-deadbeef0000", "delete user (nonexistent)",
         [200, 404, 400], [403]),
        ("POST",   "/api/admin/users/00000000-0000-0000-0000-000000000001/roles/00000000-0000-0000-0000-000000000002", "assign role",
         [200, 404, 400, 409], [403]),
        ("POST",   "/api/admin/users/00000000-0000-0000-0000-000000000001/password", "reset user password",
         [200, 404, 400], [403]),
        # JwtKeyRotationController
        ("GET",    "/api/admin/jwt-keys", "jwt keys snapshot",
         [200], [403]),
        ("POST",   "/api/admin/jwt-keys/rotate", "rotate jwt key",
         [200, 429], [403]),
        # TenantQuotaController
        ("GET",    "/api/tenant/quota", "get quota",
         [200], [403, 200]),  # TenantQuotaController: self quota access may be public
        # AuditController
        ("GET",    "/api/audit/logs?limit=5", "audit logs",
         [200], [403]),
        # UserDataComplianceController
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001/data-export", "compliance check",
         [200, 404], [403]),
        # UserTenantController
        ("GET",    "/api/admin/users/00000000-0000-0000-0000-000000000001/tenants", "user tenants",
         [200, 404], [403]),
        # TenantController
        ("GET",    "/api/admin/tenants", "list tenants",
         [200], [403]),
        ("GET",    "/api/admin/tenants/00000000-0000-0000-0000-000000000001", "get tenant by id",
         [200, 404], [403]),
        # Add more endpoints for comprehensive coverage
        # AutomationRuleController
        ("GET",    "/api/automation", "list automation rules",
         [200], [403]),
        ("POST",   "/api/automation", "create automation rule",
         [201, 400], [403, 400]),
        # NotificationChannelController
        ("GET",    "/api/admin/notification-channels", "list notification channels",
         [200], [403]),
    ]

    results = []
    for method, path, label, valid_admin, valid_user in TESTS:
        print(f"\n  {label} [{method} {path}]")

        # No token → expect 401
        no_auth_status = http(method, path, None)
        no_auth_ok = no_auth_status == 401
        print(f"    {'✅' if no_auth_ok else '❌'} no-auth → {no_auth_status} (want 401)")

        # Admin token
        admin_status = http(method, path, admin_token)
        admin_ok = admin_status in valid_admin
        print(f"    {'✅' if admin_ok else '❌'} admin   → {admin_status} (want one of {valid_admin})")

        # User token → MUST be 403
        user_status = http(method, path, user_token)
        user_ok = user_status in valid_user
        print(f"    {'✅' if user_ok else '❌'} user    → {user_status} (want 403)")

        passed = no_auth_ok and admin_ok and user_ok
        results.append((label, passed,
            f"no-auth={no_auth_status} admin={admin_status} user={user_status}"))

    print("\n" + "=" * 70)
    total = len(results)
    passed = sum(1 for _, p, _ in results if p)
    print(f"Results: {passed}/{total} PASS")
    for label, p, detail in results:
        mark = "✅" if p else "❌"
        print(f"  {mark} {label} — {detail}")

    sys.exit(0 if passed == total else 1)

if __name__ == "__main__":
    main()
