package com.nocobase.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** destination 拼装与租户解析(租户隔离的基础)。 */
class StompDestinationsTest {

    @Test
    void channelTopic_containsTenantAndChannel() {
        UUID id = UUID.randomUUID();
        assertThat(StompDestinations.channelTopic("tenant_acme", id))
                .isEqualTo("/topic/t-tenant_acme.channel." + id);
    }

    @Test
    void alertsTopic_containsTenant() {
        assertThat(StompDestinations.alertsTopic("t1")).isEqualTo("/topic/t-t1.alerts");
    }

    @Test
    void userQueue_containsTenantAndUser() {
        UUID uid = UUID.randomUUID();
        assertThat(StompDestinations.userQueue("tenant_default", uid))
                .isEqualTo("/queue/t-tenant_default.user." + uid);
    }

    @Test
    void parseTenantId_extractsTenant() {
        assertThat(StompDestinations.parseTenantId("/topic/t-tenant_default.channel.abc"))
                .isEqualTo("tenant_default");
    }

    @Test
    void parseTenantId_handlesUnderscoreInTenantId() {
        // tenantId 自身含下划线但不含点,必须能正确截断
        assertThat(StompDestinations.parseTenantId("/topic/t-tenant_acme.alerts"))
                .isEqualTo("tenant_acme");
    }

    @Test
    void parseTenantId_returnsNullWhenMissing() {
        assertThat(StompDestinations.parseTenantId(null)).isNull();
        assertThat(StompDestinations.parseTenantId("")).isNull();
        assertThat(StompDestinations.parseTenantId("/topic/channel.abc")).isNull();
    }
}
