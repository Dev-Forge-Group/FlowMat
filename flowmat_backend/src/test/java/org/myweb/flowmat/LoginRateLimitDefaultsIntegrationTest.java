package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LoginRateLimitDefaultsIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private StringRedisTemplate redis;

    @Test
    void theDefaultAccountLimitStillBlocksTheNinthAttempt() throws Exception {
        String account = "rate-default-" + UUID.randomUUID();
        // No account has this name, yet it locks as an account would: wrong four times, then locked from the fifth
        // (docs/operations/login-security.md §2). The rate limit still counts every attempt.
        for (int attempt = 0; attempt < 8; attempt++) login(account, "192.0.2.101", attempt < 4 ? 401 : 403);
        login(account, "192.0.2.101", 429);
        String key = "auth:ratelimit:login-account:" + account;
        assertThat(redis.opsForValue().get(key)).isEqualTo("9");
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(600L);
    }

    @Test
    void theDefaultIpLimitStillBlocksTheThirteenthAttemptAcrossAccounts() throws Exception {
        String account = "rate-default-ip-" + UUID.randomUUID();
        for (int attempt = 0; attempt < 12; attempt++) login(account + "-" + attempt, "192.0.2.102", 401);
        login(account + "-blocked", "192.0.2.102", 429);
        String key = "auth:ratelimit:login-ip:192.0.2.102";
        assertThat(redis.opsForValue().get(key)).isEqualTo("13");
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(600L);
    }

    private void login(String account, String ip, int expectedStatus) throws Exception {
        mockMvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIdOrEmail\":\"" + account + "\",\"password\":\"unused-test-password\"}"))
            .andExpect(status().is(expectedStatus));
    }
}
