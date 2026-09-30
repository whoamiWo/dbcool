package com.nocobase.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
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
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void loginExceedsLimit_returns429() throws Exception {
        for (int i = 0; i < 5; i++) {
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
