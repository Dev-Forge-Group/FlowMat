package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.auth.login-rate-limit.account-limit=100",
    "app.auth.login-rate-limit.ip-limit=200"
})
class LoginRateLimitCiIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private StringRedisTemplate redis;

    @Test
    void theCiAccountLimitAllowsOneHundredAttemptsAndStillBlocksTheNext() throws Exception {
        String account = "rate-ci-" + UUID.randomUUID();
        // No account has this name, yet it locks as an account would: wrong four times, then locked from the fifth
        // (docs/operations/login-security.md §2). The rate limit still counts every attempt.
        for (int attempt = 0; attempt < 100; attempt++) login(account, "192.0.2.103", attempt < 4 ? 401 : 403);
        login(account, "192.0.2.103", 429);
        String key = "auth:ratelimit:login-account:" + account;
        assertThat(redis.opsForValue().get(key)).isEqualTo("101");
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(600L);
    }

    @Test
    void theCiIpLimitAllowsTwoHundredAttemptsAcrossAccountsAndStillBlocksTheNext() throws Exception {
        String account = "rate-ci-ip-" + UUID.randomUUID();
        for (int attempt = 0; attempt < 200; attempt++) login(account + "-" + attempt, "192.0.2.104", 401);
        login(account + "-blocked", "192.0.2.104", 429);
        String key = "auth:ratelimit:login-ip:192.0.2.104";
        assertThat(redis.opsForValue().get(key)).isEqualTo("201");
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(600L);
    }

    private void login(String account, String ip, int expectedStatus) throws Exception {
        mockMvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIdOrEmail\":\"" + account + "\",\"password\":\"unused-test-password\"}"))
            .andExpect(status().is(expectedStatus));
    }
}
