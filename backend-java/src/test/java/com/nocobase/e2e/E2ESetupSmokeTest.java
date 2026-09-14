package com.nocobase.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class E2ESetupSmokeTest {
    @Test
    void contextLoads() {}
}
