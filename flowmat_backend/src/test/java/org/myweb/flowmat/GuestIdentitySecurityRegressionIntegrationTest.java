package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.application.AuthService;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.myweb.flowmat.global.websocket.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class GuestIdentitySecurityRegressionIntegrationTest extends IntegrationTestSupport {

    private static final String TEST_SECRET = "smoke-test-secret-key-that-is-at-least-32-bytes";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;

    @Test
    void callerChosenSidCannotImpersonateARegisteredProjectOwner() throws Exception {
        String sid = sid();
        String owner = "GUEST_" + sid;
        String project = registeredOwnerProject(owner);
        MvcResult result = guest(new Cookie("guest_sid", sid));
        String token = access(result);
        assertThat(owner.equals(jwt.resolveUserId(token))).as("Caller cannot choose registered identity").isFalse();
        JsonNode visible = mapper.readTree(mvc.perform(get("/projects")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(visible.findValuesAsText("projectId").contains(project)).isFalse();
    }

    @Test
    void knowingAGuestSubjectDoesNotAllowMintingThatIdentity() throws Exception {
        String first = jwt.resolveUserId(access(guest()));
        String knownSid = first.substring("GUEST_".length());
        String second = jwt.resolveUserId(access(guest(new Cookie("guest_sid", knownSid))));
        assertThat(first.equals(second)).as("Unsigned subject is not a guest credential").isFalse();
    }

    @Test
    void unsignedCookieAndLegacyBearerCannotBootstrapTheOldIdentity() throws Exception {
        String sid = sid();
        String legacy = jwt.generateAccessToken("GUEST_" + sid, "guest");
        MvcResult result = mvc.perform(post("/auth/guest-token").servletPath("/auth/guest-token")
                .cookie(new Cookie("guest_sid", sid))
                .header("Authorization", "Bearer " + legacy)).andExpect(status().isOk()).andReturn();
        assertThat(jwt.resolveUserId(access(result)).equals("GUEST_" + sid)).isFalse();
        assertThat(jwt.isAccessToken(access(result))).isTrue();
    }

    @Test
    void signedCookieAndFreshVerifiedBearerReuseTheAccessToken() throws Exception {
        String sid = sid();
        String proof = signed(sid, "guest-session", "guest", null, 60_000);
        String bearer = jwt.generateGuestAccessToken("GUEST_" + sid, 600_000);
        MvcResult result = mvc.perform(post("/auth/guest-token").cookie(new Cookie("guest_sid", proof))
                .header("Authorization", "Bearer " + bearer)).andExpect(status().isOk()).andReturn();
        assertThat(access(result).equals(bearer)).as("A matching fresh credential is reused").isTrue();
    }

    @Test
    void issuedGuestAccessAuthenticatesForHttpAndStomp() throws Exception {
        String token = access(guest());
        assertThat(jwt.isAccessToken(token)).isTrue();
        assertThat(jwt.getAuthentication(token).isAuthenticated()).isTrue();
        connect(token);
    }

    @Test
    void aValidSignedSessionProofKeepsItsIdentity() throws Exception {
        String sid = sid();
        String proof = signed(sid, "guest-session", "guest", null, 60_000);
        String identity = jwt.resolveUserId(access(guest(new Cookie("guest_sid", proof))));
        assertThat(identity.equals("GUEST_" + sid)).isTrue();
    }

    @Test
    void issuedCookieIsSignedAndAllowsStableContinuation() throws Exception {
        MvcResult first = guest();
        Cookie cookie = first.getResponse().getCookie("guest_sid");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue().split("\\.").length == 3).as("Cookie carries a signed session proof").isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        String before = jwt.resolveUserId(access(first));
        String after = jwt.resolveUserId(access(guest(cookie)));
        assertThat(before.equals(after)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "refresh", "oauth-signup"})
    void otherTokenPurposesCannotSelectTheGuestSession(String purpose) throws Exception {
        String sid = sid();
        String wrong = signed(sid, purpose, "guest", Boolean.TRUE, 60_000);
        String actual = jwt.resolveUserId(access(guest(new Cookie("guest_sid", wrong))));
        assertThat(actual.equals("GUEST_" + sid)).isFalse();
    }

    @Test
    void expiredAndTamperedProofsRotateToANewIdentity() throws Exception {
        String sid = sid();
        String expired = signed(sid, "guest-session", "guest", null, -1_000);
        String valid = signed(sid, "guest-session", "guest", null, 60_000);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String proof : new String[] {expired, String.join(".", parts)}) {
            String actual = jwt.resolveUserId(access(guest(new Cookie("guest_sid", proof))));
            assertThat(actual.equals("GUEST_" + sid)).isFalse();
        }
    }

    @Test
    void legacyGuestAccessIsRejectedByHttpAndStomp() throws Exception {
        String legacy = jwt.generateAccessToken("GUEST_" + sid(), "guest");
        assertThat(jwt.isAccessToken(legacy)).as("Legacy guest tokens lack proof of a server-issued session").isFalse();
        mvc.perform(get("/projects").header("Authorization", "Bearer " + legacy))
            .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> connect(legacy)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aStringMarkerCannotAuthenticateAsAGuest() {
        String token = signed("GUEST_" + sid(), "access", "guest", "true", 60_000);
        assertThat(jwt.isAccessToken(token)).isFalse();
        assertThatThrownBy(() -> jwt.getAuthentication(token)).isInstanceOf(BusinessException.class);
    }

    @Test
    void sessionProofIsNeitherAnApiAccessNorARefreshCredential() throws Exception {
        String proof = signed(sid(), "guest-session", "guest", null, 60_000);
        assertThat(jwt.isAccessToken(proof)).isFalse();
        assertThat(jwt.isRefreshToken(proof)).isFalse();
        mvc.perform(get("/projects").header("Authorization", "Bearer " + proof))
            .andExpect(status().isUnauthorized());
        Cookie csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk())
            .andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/auth/refresh").header("Authorization", "Bearer " + proof)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> connect(proof)).isInstanceOf(BusinessException.class);
    }

    @Test
    void signedSessionCannotCollideWithAnExistingRegisteredUser() {
        String sid = sid();
        registeredOwnerProject("GUEST_" + sid);
        assertThatThrownBy(() -> auth.issueGuestToken(sid, null))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GUEST_reserved", "guest_reserved", " Guest_reserved "})
    void registeredSignupReservesTheGuestNamespace(String id) throws Exception {
        MvcResult result = mvc.perform(post("/auth/signup").contentType("application/json").content(mapper.writeValueAsString(
                new UserSignupRequest(id, "Guest boundary", "Boundary", "boundary@example.test",
                    telephone(), LocalDate.of(1990, 1, 1), "NotARealPassword123!"))))
            .andExpect(status().isBadRequest()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("message")
            .asText().contains("reserved")).isTrue();
        assertThat(users.existsByUserId(id.trim())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "kakao"})
    void oauthSignupReservesTheSameNamespace(String provider) {
        String token = jwt.generateOauthSignupToken(provider, sid(), "boundary@example.test", "Boundary");
        OAuthSignupCompleteRequest request = new OAuthSignupCompleteRequest(token, "guest_" + sid(),
            "Guest boundary", "Boundary" + sid(), telephone(), LocalDate.of(1990, 1, 1), "NotARealPassword123!");
        assertThatThrownBy(() -> {
            if (provider.equals("google")) auth.completeGoogleSignup(request, "test", "127.0.0.1");
            else auth.completeKakaoSignup(request, "test", "127.0.0.1");
        }).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void existingReservedNameAccountsStillAuthenticateAsOrdinaryUsers() throws Exception {
        String owner = "GUEST_" + sid();
        String project = registeredOwnerProject(owner);
        String token = jwt.generateAccessToken(owner, "user");
        assertThat(jwt.isAccessToken(token)).isTrue();
        JsonNode visible = mapper.readTree(mvc.perform(get("/projects")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(visible.findValuesAsText("projectId").contains(project)).isTrue();
    }

    private MvcResult guest(Cookie... cookies) throws Exception {
        var request = post("/auth/guest-token");
        if (cookies.length > 0) request.cookie(cookies);
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private String access(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private String registeredOwnerProject(String owner) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId(owner);
        user.setUserName("Identity boundary");
        user.setUserEmail(sid() + "@example.test");
        user.setUserPwd("unused-test-fixture");
        user.setUserBirth(LocalDate.of(1990, 1, 1));
        user.setUserTel(telephone());
        user.setPwdUpdatedAt(OffsetDateTime.now());
        user.setFailedLoginCount(0);
        user.setUserRole("user");
        user.setUserStatus("active");
        user.setDeleteYn("N");
        users.saveAndFlush(user);
        Project project = new Project();
        project.setProjectId("p_" + sid());
        project.setProjectName("Identity boundary");
        project.setOwnerId(owner);
        project.setProjectStatus("active");
        project.setVisibility("private");
        project.setDeletedYn("N");
        projects.saveAndFlush(project);
        return project.getProjectId();
    }

    private String signed(String subject, String type, String role, Object marker, long lifetime) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder().subject(subject).claim("typ", type).claim("role", role)
            .issuedAt(new Date(now - 10_000)).expiration(new Date(now + lifetime));
        if (marker != null) builder.claim("guestSessionVerified", marker);
        return builder.signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    private void connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        new StompAuthChannelInterceptor(jwt, mock(WorkflowAccess.class)).preSend(message, null);
    }

    private static String sid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String telephone() {
        return "010" + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000_000L);
    }
}
