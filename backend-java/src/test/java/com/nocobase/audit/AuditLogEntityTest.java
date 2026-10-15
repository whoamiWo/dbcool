package com.nocobase.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** AuditLogEntity getter/setter 全量覆盖（PHASE105 audit 包 97% 门禁收口）。 */
class AuditLogEntityTest {

    @Test
    void gettersReturnSetValues() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.now();
        AuditLogEntity e = new AuditLogEntity();
        e.setId(id);
        e.setTenantId("t1");
        e.setUserId("u1");
        e.setUsername("alice");
        e.setAction("create");
        e.setResource("order");
        e.setResourceId("r-1");
        e.setPayloadJson("{\"a\":1}");
        e.setIp("1.2.3.4");
        e.setUserAgent("curl/8");
        e.setCreatedAt(ts);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getUserId()).isEqualTo("u1");
        assertThat(e.getUsername()).isEqualTo("alice");
        assertThat(e.getAction()).isEqualTo("create");
        assertThat(e.getResource()).isEqualTo("order");
        assertThat(e.getResourceId()).isEqualTo("r-1");
        assertThat(e.getPayloadJson()).isEqualTo("{\"a\":1}");
        assertThat(e.getIp()).isEqualTo("1.2.3.4");
        assertThat(e.getUserAgent()).isEqualTo("curl/8");
        assertThat(e.getCreatedAt()).isEqualTo(ts);
    }
}
