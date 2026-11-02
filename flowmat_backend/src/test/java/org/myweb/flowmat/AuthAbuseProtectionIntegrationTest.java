package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetConfirmRequest;
import org.myweb.flowmat.domain.user.application.AuthRedisStore;
import org.myweb.flowmat.domain.user.application.AuthService;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Guessing and flooding on the public auth endpoints, and the sessions a password reset leaves behind (2026-11-01 audit
 * B1, B2, B4). Every test uses its own documentation address so the shared Redis limits of other classes stay untouched.
 */
@AutoConfigureMockMvc
class AuthAbuseProtectionIntegrationTest extends IntegrationTestSupport {

    private static final AtomicInteger ADDRESS = new AtomicInteger(10);

    @Autowired MockMvc mvc;
    @Autowired AuthRedisStore store;
    @Autowired AuthService auth;
    @Autowired UserRepository users;

    @Test
    void fiveWrongCodesDeleteTheCodeSoTheRightOneFailsToo() throws Exception {
        String email = unique() + "@example.test";
        store.saveEmailCode(email, "123456", Duration.ofMinutes(5), Duration.ofSeconds(1));
        String ip = address();
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(json(post("/auth/email/verify-code"), ip, verifyBody(email, "00000" + attempt))).andExpect(status().isBadRequest());
        }
        mvc.perform(json(post("/auth/email/verify-code"), ip, verifyBody(email, "123456"))).andExpect(status().isBadRequest());
        assertThat(store.isEmailVerified(email)).isFalse();
    }

    @Test
    void theRightCodeStillWorksBeforeTheLimit() throws Exception {
        String email = unique() + "@example.test";
        store.saveEmailCode(email, "654321", Duration.ofMinutes(5), Duration.ofSeconds(1));
        String ip = address();
        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(json(post("/auth/email/verify-code"), ip, verifyBody(email, "00000" + attempt))).andExpect(status().isBadRequest());
        }
        mvc.perform(json(post("/auth/email/verify-code"), ip, verifyBody(email, "654321"))).andExpect(status().isOk());
        assertThat(store.isEmailVerified(email)).isTrue();
    }

    @Test
    void publicAuthEndpointsAnswer429OnceAnAddressPassesItsLimit() throws Exception {
        assertLimited("/auth/email/verify-code", 30, n -> verifyBody("v" + n + unique() + "@example.test", "000000"));
        assertLimited("/auth/find-email", 10, n -> "{\"userName\":\"Nobody\",\"userTel\":\"0109" + String.format("%07d", n) + "\"}");
        assertLimited("/auth/reset-password/request", 10, n -> "{\"userEmail\":\"r" + n + unique() + "@example.test\"}");
        assertLimited("/auth/signup", 20, n -> signupBody(n));
        assertLimited("/auth/guest-token", 60, n -> null);
    }

    @Test
    void aForgedForwardedForHeaderDoesNotOpenAFreshLimit() throws Exception {
        String ip = address();
        for (int n = 1; n <= 10; n++) {
            int answer = mvc.perform(json(post("/auth/reset-password/request"), ip, "{\"userEmail\":\"x" + n + unique() + "@example.test\"}")
                .header("X-Forwarded-For", "198.51.100." + n)).andReturn().getResponse().getStatus();
            assertThat(answer).isNotEqualTo(429);
        }
        mvc.perform(json(post("/auth/reset-password/request"), ip, "{\"userEmail\":\"y" + unique() + "@example.test\"}")
            .header("X-Forwarded-For", "198.51.100.200")).andExpect(status().isTooManyRequests());
    }

    @Test
    void confirmingAPasswordResetRevokesEveryRefreshToken() {
        String userId = "reset_" + unique().substring(0, 12);
        saveUser(userId);
        store.storeRefreshToken("jti-a-" + userId, userId, "device-a", Duration.ofDays(30));
        store.storeRefreshToken("jti-b-" + userId, userId, "device-b", Duration.ofDays(30));
        String token = store.createPasswordResetToken(userId, Duration.ofMinutes(30));

        auth.confirmPasswordReset(new PasswordResetConfirmRequest(token, "New-password-2026!"));

        assertThat(store.getRefreshToken("jti-a-" + userId)).isNull();
        assertThat(store.getRefreshToken("jti-b-" + userId)).isNull();
        assertThat(store.getPasswordResetUserId(token)).isNull();
    }

    private void assertLimited(String path, int limit, IntFunction<String> body) throws Exception {
        String ip = address();
        for (int n = 1; n <= limit; n++) {
            int answer = mvc.perform(json(post(path), ip, body.apply(n))).andReturn().getResponse().getStatus();
            assertThat(answer).as(path + " request " + n).isNotEqualTo(429);
        }
        mvc.perform(json(post(path), ip, body.apply(limit + 1))).andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String ip, String body) {
        request.with(sent -> { sent.setRemoteAddr(ip); return sent; });
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String verifyBody(String email, String code) {
        return "{\"userEmail\":\"" + email + "\",\"code\":\"" + code + "\"}";
    }

    private static String signupBody(int n) {
        String id = "s" + n + unique().substring(0, 10);
        return "{\"userId\":\"" + id + "\",\"userName\":\"Signup " + n + "\",\"userNickname\":\"" + id + "\",\"userEmail\":\""
            + id + "@example.test\",\"userTel\":\"0108" + String.format("%07d", n) + "\",\"userBirth\":\"1990-01-01\",\"password\":\"Passw0rd!2026\"}";
    }

    private static String address() {
        return "203.0.113." + ADDRESS.getAndIncrement();
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void saveUser(String userId) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId(userId);
        user.setUserName("Reset fixture");
        user.setUserEmail(userId + "@example.test");
        user.setUserPwd("unused-test-fixture");
        user.setUserBirth(LocalDate.of(1990, 1, 1));
        user.setUserTel("010" + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L));
        user.setPwdUpdatedAt(OffsetDateTime.now());
        user.setFailedLoginCount(0);
        user.setUserRole("user");
        user.setUserStatus("active");
        user.setDeleteYn("N");
        users.saveAndFlush(user);
    }
}
