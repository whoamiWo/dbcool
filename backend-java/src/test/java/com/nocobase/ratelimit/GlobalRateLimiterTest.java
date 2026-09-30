package com.nocobase.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlobalRateLimiterTest {

    private GlobalRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new GlobalRateLimiter(null);
    }

    @Test
    void allowRequest_withinLimit() {
        String key = "test:user1:/api/auth/login";
        assertThat(rateLimiter.allowRequest(key, 2, 60)).isTrue();
        assertThat(rateLimiter.allowRequest(key, 2, 60)).isTrue();
    }

    @Test
    void allowRequest_exceedsLimit_returnsFalse() {
        String key = "test:user2:/api/auth/login";
        assertThat(rateLimiter.allowRequest(key, 2, 60)).isTrue();
        assertThat(rateLimiter.allowRequest(key, 2, 60)).isTrue();
        assertThat(rateLimiter.allowRequest(key, 2, 60)).isFalse();
    }

    @Test
    void allowRequest_differentKeysAreIsolated() {
        String key1 = "test:user3:/api/auth/login";
        String key2 = "test:user4:/api/auth/login";
        
        assertThat(rateLimiter.allowRequest(key1, 1, 60)).isTrue();
        assertThat(rateLimiter.allowRequest(key1, 1, 60)).isFalse();
        
        assertThat(rateLimiter.allowRequest(key2, 1, 60)).isTrue();
    }

    @Test
    void allowRequest_nullKey_allows() {
        assertThat(rateLimiter.allowRequest(null, 1, 60)).isTrue();
        assertThat(rateLimiter.allowRequest("", 1, 60)).isTrue();
        assertThat(rateLimiter.allowRequest("   ", 1, 60)).isTrue();
    }
}