package com.nocobase.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ratelimit.login.limit=3",
    "ratelimit.login.windowSeconds=300"
})
class GlobalRateLimitFilterE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GlobalRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.reset("ip:127.0.0.1:/api/auth/login");
    }

    @Test
    void loginWithinLimit_returns401Not429() throws Exception {
        for (int i = 0; i < 2; i++) {
            int status = mockMvc.perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().is4xxClientError())
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).isNotEqualTo(429);
        }
    }

    @Test
    void loginExceedsLimit_returns429() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().is4xxClientError());
        }

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
