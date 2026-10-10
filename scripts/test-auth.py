#!/usr/bin/env python3
import urllib.request
import json
import base64

def login(username, password):
    data = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(
        "http://localhost:8080/api/auth/login",
        data=data,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read().decode())
        return result["data"]["access_token"]

def decode_token(token):
    parts = token.split(".")
    if len(parts) >= 2:
        payload_b64 = parts[1]
        payload_b64 += "=" * (4 - len(payload_b64) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_b64))
        return payload
    return None

# Test admin login
print("Testing admin login...")
admin_token = login("admin", "admin123")
print(f"Admin token obtained: {admin_token[:50]}...")

payload = decode_token(admin_token)
print(f"JWT payload: {payload}")
print(f"Has roles claim: {'roles' in payload}")
if 'roles' in payload:
    print(f"Roles: {payload['roles']}")
else:
    print("No roles claim in token!")

# Test regular user login
print("\nTesting regular user login...")
try:
    user_token = login("testuser", "test123")
    print(f"User token obtained: {user_token[:50]}...")
    payload = decode_token(user_token)
    print(f"JWT payload: {payload}")
    if 'roles' in payload:
        print(f"Roles: {payload['roles']}")
    else:
        print("No roles claim in token!")
except Exception as e:
    print(f"User login failed: {e}")
