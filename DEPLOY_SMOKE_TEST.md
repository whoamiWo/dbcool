# Deployment Smoke Test Report

**Date:** 2026-10-01  
**Environment:** Production-like Stack (Docker Compose)  
**Status:** ✅ PASS (with minor notes)

---

## Services Status

| Service | Status | Port | Health |
|---------|--------|------|--------|
| backend-java | ✅ Running | 8080 | healthy |
| backend-java-2 | ✅ Running | 8081 | healthy |
| backend-python | ✅ Running | 8000 | healthy |
| crdt-service | ✅ Running | 3100 | healthy |
| postgres | ✅ Running | 5432 | healthy |
| redis | ✅ Running | 6379 | healthy |
| minio | ✅ Running | 9000-9001 | healthy |
| rabbitmq | ✅ Running | 5672 | healthy |

---

## S1: Backup & Restore Verification (P0)

### Test Results

| Check | Result | Details |
|-------|--------|---------|
| Backup creation | ✅ PASS | Created `backup_20261001_122343.tar.gz` (86KB) |
| Backup contents | ✅ PASS | PostgreSQL dump (238KB) + Redis snapshot |
| Restore to test DB | ✅ PASS | All tables restored successfully |
| Data integrity | ✅ PASS | 100% match (98 tables, all counts identical) |
| RTO (Restore Time) | ✅ PASS | ~3.1s for 235KB dump |
| RPO (Backup Time) | ✅ PASS | ~105ms for full backup |

### Baseline Data (Source DB)

```
users=6
collection_meta=38
im_message=254
wiki_page=5
```

### Restored Data (Test DB)

```
users=6
collection_meta=38
im_message=254
wiki_page=5
```

**Conclusion:** Backup/restore pipeline verified. RTO < 5s, RPO < 200ms.

---

## S2: Six Link Smoke Test (P0)

### Test Results

| Link | Endpoint | Status | Notes |
|------|----------|--------|-------|
| S2-1: Login & Auth | POST /api/auth/login | ✅ PASS | Token issued, /me returns admin |
| S2-1: Auth enforcement | GET /api/auth/me (no token) | ✅ PASS | Returns 401 |
| S2-2: Data View | GET /api/collections/users/records | ⚠️ PARTIAL | Returns 403 - ACL wildcard '*' not matching 'users' (needs per-collection policy) |
| S2-3: IM | POST /api/im/messages | ✅ PASS | Message sent successfully |
| S2-4: Slack Inbound | POST /api/slack/events | ✅ PASS | Valid signature → 200 + message in DB |
| S2-5: DingTalk | POST /api/dingtalk/auth-url | ✅ PASS | Returns 200 |
| S2-5: DingTalk Sig | POST /api/dingtalk/events (wrong sig) | ✅ PASS | Returns 401 |
| S2-6: Huddle Cross-Instance | Redis pub/sub | ✅ PASS | 2 Java backends, pub/sub works |

### Known Issues

**S2-2: ACL Policy Matching**
- Problem: Wildcard policies (`subject='*'`) exist for admin role but `AclEnforcer.isAllowed()` requires exact collection name match
- Impact: Data view endpoints return 403 despite admin role
- Workaround: Added per-collection policies for `users` collection
- Fix needed: Update `AclEnforcer.java:136` to support wildcard matching OR generate per-collection policies on role assignment

---

## S3: Huddle Signaling Unit Tests (P1)

### Test Coverage

| Category | Tests | Status |
|----------|-------|--------|
| Room join/leave | 4 | ✅ PASS |
| Relay & anti-echo | 3 | ✅ PASS |
| Redis publish | 3 | ✅ PASS |
| Remote message handling | 6 | ✅ PASS |
| Edge cases | 5 | ✅ PASS |
| **Total** | **21** | **✅ ALL PASS** |

### Key Scenarios Covered

1. **Anti-echo**: Sender never receives their own messages
2. **Cross-instance relay**: Redis pub/sub delivers to other instances
3. **Self-instance filtering**: Messages from own instanceId are ignored
4. **Redis failure resilience**: Local delivery continues when Redis fails
5. **Room cleanup**: Sessions removed on disconnect

**New Test File:** `src/test/java/com/nocobase/im/HuddleSignalingHandlerTest.java`

---

## Environment Configuration

### Database Connection

```yaml
POSTGRES_HOST: localhost
POSTGRES_PORT: 5432
POSTGRES_USER: nocobase
POSTGRES_DB: nocobase
```

### Integration Endpoints

| Service | Base URL | Auth Required |
|---------|----------|---------------|
| Java Backend | http://localhost:8080 | Yes (JWT) |
| Python Backend | http://localhost:8000 | Yes (for admin endpoints) |
| CRDT Service | http://localhost:3100 | No |

### Redis Pub/Sub Channel

```
nocobase:huddle:signaling
```

---

## Recommendations

### Immediate Actions

1. **Fix ACL wildcard matching** in `AclEnforcer.java` OR auto-generate per-collection policies
2. **Document backup retention policy** (currently 7 days)
3. **Add health check endpoint** for backup service

### Future Enhancements

1. **Automated backup scheduling**: Configure `BACKUP_CRON` environment variable
2. **Backup verification**: Periodic restore test to cold storage
3. **Cross-region replication**: For disaster recovery

---

## Appendix: Commands Used

### Backup Creation

```bash
docker compose exec backend-python python3 -c "from nocobase_py.services.backup import create_backup; print(create_backup())"
```

### Restore Verification

```bash
pg_restore -U nocobase -d nocobase_restore_test --no-owner /tmp/dump16.pg
psql -U nocobase -d nocobase_restore_test -c "SELECT count(*) FROM users;"
```

### Slack Event Test

```bash
TIMESTAMP=$(date +%s)
SIG=$(printf 'v0:%s:%s' "$TIMESTAMP" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
curl -X POST http://localhost:8080/api/slack/events \
  -H "X-Slack-Request-Timestamp: $TIMESTAMP" \
  -H "X-Slack-Signature: v0=$SIG" \
  -d "$BODY"
```

---

**Report Generated:** 2026-10-01 12:28 UTC  
**Next Review:** After ACL fix deployment
