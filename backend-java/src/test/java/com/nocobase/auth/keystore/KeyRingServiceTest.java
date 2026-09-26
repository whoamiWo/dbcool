package com.nocobase.auth.keystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class KeyRingServiceTest {

    private static final String STRONG_SECRET = "this-is-a-strong-test-secret-of-at-least-32-bytes!";

    private KeyRingService newRing() {
        return new KeyRingService(STRONG_SECRET, "", mockEnv());
    }

    private KeyRingService newRingWithPrevious() {
        return new KeyRingService(STRONG_SECRET, "previous-secret-thats-also-long-enough-1234", mockEnv());
    }

    private static Environment mockEnv() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        return env;
    }

    @Test
    void start_withActiveKey() {
        KeyRingService r = newRing();
        assertThat(r.size()).isEqualTo(1);
        assertThat(r.currentActive()).isPresent();
        assertThat(r.currentActive().get().canSign()).isTrue();
        assertThat(r.currentActive().get().canVerify()).isTrue();
    }

    @Test
    void start_withPreviousKey_bothLoaded() {
        KeyRingService r = newRingWithPrevious();
        assertThat(r.size()).isEqualTo(2);
        boolean foundRetired = r.snapshot().stream()
                .anyMatch(m -> "RETIRED".equals(m.get("status")));
        assertThat(foundRetired).isTrue();
    }

    @Test
    void weakSecret_doesNotFailStartup() {
        KeyRingService r = new KeyRingService("weak!!!", "", mockEnv());
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void rotate_generatesNewActiveAndRetiresOld() {
        KeyRingService r = newRing();
        String oldKid = r.currentActive().get().kid();
        String newKid = r.rotate();
        assertThat(newKid).isNotEqualTo(oldKid);
        assertThat(r.size()).isEqualTo(2);

        KeyRingEntry old = r.findByKid(oldKid).get();
        assertThat(old.canVerify()).isTrue();
        assertThat(old.canSign()).isFalse();
        assertThat(old.status()).isEqualTo(KeyRingEntry.Status.RETIRED);

        assertThat(r.currentActive().get().kid()).isEqualTo(newKid);
    }

    @Test
    void rotate_rateLimitBlocks() {
        KeyRingService r = newRing();
        r.rotate();
        assertThatThrownBy(r::rotate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("速率超限");
    }

    @Test
    void revokeUnknownKid_throws() {
        KeyRingService r = newRing();
        assertThatThrownBy(() -> r.revoke("ghost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeActiveKid_throws() {
        KeyRingService r = newRing();
        String kid = r.currentActive().get().kid();
        assertThatThrownBy(() -> r.revoke(kid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能撤销当前 active");
    }

    @Test
    void revokeRetiredKid_marksRevoked() {
        KeyRingService r = newRing();
        String oldKid = r.currentActive().get().kid();
        r.rotate();
        r.revoke(oldKid);
        KeyRingEntry e = r.findByKid(oldKid).get();
        assertThat(e.status()).isEqualTo(KeyRingEntry.Status.REVOKED);
        assertThat(e.canVerify()).isFalse();
        assertThat(e.canSign()).isFalse();
    }

    @Test
    void findByKid_nullReturnsEmpty() {
        KeyRingService r = newRing();
        assertThat(r.findByKid(null)).isEmpty();
    }

    @Test
    void snapshot_secretsAreMasked() {
        KeyRingService r = newRing();
        for (Map<String, Object> m : r.snapshot()) {
            Object preview = m.get("secretPreview");
            assertThat(preview).asString().doesNotContain(STRONG_SECRET);
            assertThat(preview).asString().endsWith("...");
        }
    }

    @Test
    void randomHexSecret_isCryptographicallyLong() {
        String s = KeyRingService.randomHexSecret(KeyRingService.NEW_SECRET_BYTES);
        assertThat(s.length()).isEqualTo(KeyRingService.NEW_SECRET_BYTES * 2);
        assertThat(s).matches("[0-9a-f]+");
    }

    @Test
    void multipleRotates_eachHasUniqueKid() throws Exception {
        KeyRingService r = newRing();
        Field f = KeyRingService.class.getDeclaredField("lastRotationAt");
        f.setAccessible(true);
        f.setLong(r, 0);

        String k1 = r.rotate();
        f.setLong(r, 0);
        String k2 = r.rotate();
        assertThat(k1).isNotEqualTo(k2);
        assertThat(r.size()).isEqualTo(3);
    }
}
