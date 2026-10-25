package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.ConnectorCredentials;
import org.myweb.flowmat.domain.connector.application.ConnectorHttpProperties;
import org.myweb.flowmat.domain.connector.application.ConnectorHttpTransport;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ConnectorProfileReadinessIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "https://readiness.example.test";
    private static final String ROUTE = "/projects/" + DEMO_PROJECT + "/http-profiles";
    private static final String BODY = "{\"name\":\"Readiness\",\"baseOrigin\":\"" + ORIGIN
        + "\",\"credentialAlias\":null,\"enabled\":true,\"operations\":[{\"operationId\":\"send\","
        + "\"method\":\"POST\",\"relativePath\":\"/jobs\",\"successStatuses\":[200]}]}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectorHttpProperties properties;
    @MockitoSpyBean ConnectorCredentials credentials;
    @MockitoBean ConnectorHttpGateway gateway;
    @MockitoBean ConnectorHttpTransport transport;

    @BeforeEach
    void configuration() {
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of(ORIGIN));
        properties.setPrivateCidrs(List.of("10.77.0.0/16"));
        properties.setAllowPrivateHttp(false);
        properties.setCredentials(Map.of());
    }

    @AfterEach
    void noCredentialResolutionOrNetwork() {
        verifyNoInteractions(credentials, gateway, transport);
        properties.setEnabled(false);
        properties.setAllowedOrigins(List.of());
        properties.setPrivateCidrs(List.of());
        properties.setCredentials(Map.of());
    }

    @Test
    void ownerChecksStaticAnonymousConfigurationWithoutWritingOrCachingAnything() throws Exception {
        JsonNode profile = create(BODY);
        List<Integer> before = tableCounts();
        var response = call(DEMO_OWNER, get(path(profile)), null)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        JsonNode result = data(response);

        assertEquals(Set.of("profileVersionId", "configurationReady", "blockers", "credentialValuesChecked", "connectionTested"),
            result.propertyStream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals(profile.path("profileVersionId").asText(), result.path("profileVersionId").asText());
        assertTrue(result.path("configurationReady").asBoolean());
        assertTrue(result.path("blockers").isEmpty());
        assertFalse(result.path("credentialValuesChecked").asBoolean());
        assertFalse(result.path("connectionTested").asBoolean());
        assertEquals(before, tableCounts());
        String wire = result.toString();
        assertFalse(wire.contains(ORIGIN));
        assertFalse(wire.contains("10.77.0.0"));
    }

    @Test
    void validBearerKeyConfigurationIsReadyEvenWithoutResolvingItsValue() throws Exception {
        register("registered", DEMO_PROJECT, "FLOWMAT_TEST_READINESS_STATIC_ONLY_TOKEN");
        JsonNode profile = create(BODY.replace("\"credentialAlias\":null", "\"credentialAlias\":\"registered\""));

        JsonNode result = data(call(DEMO_OWNER, get(path(profile)), null));

        assertTrue(result.path("configurationReady").asBoolean());
        assertFalse(result.path("credentialValuesChecked").asBoolean());
        assertFalse(result.toString().contains("FLOWMAT_TEST_READINESS_STATIC_ONLY_TOKEN"));
        assertFalse(result.toString().contains("registered"));
    }

    @Test
    void onlyTheOwnerCanInspectConfigurationAndAccessIsCheckedBeforeVersionLookup() throws Exception {
        JsonNode profile = create(BODY);
        for (String role : List.of("viewer", "editor")) {
            String member = member(role);
            call(member, get(path(profile)), null).andExpect(status().isForbidden());
            call(member, get(ROUTE + "/by-version/missing-version/readiness"), null).andExpect(status().isForbidden());
            call(member, get(ROUTE + "/by-version/" + profile.path("profileVersionId").asText() + "/metadata"), null)
                .andExpect(status().isOk());
        }
        call("readiness-outsider", get(path(profile)), null).andExpect(status().isForbidden());
        mvc.perform(get(path(profile))).andExpect(status().isUnauthorized());
    }

    @Test
    void missingVersionsAndVersionsFromAnotherOwnedProjectAreNotFound() throws Exception {
        JsonNode profile = create(BODY);
        String otherProject = data(call(DEMO_OWNER, post("/projects"),
            "{\"projectName\":\"Readiness scope\",\"ownerId\":\"" + DEMO_OWNER + "\"}"))
            .path("projectId").asText();
        properties.setEnabled(false);

        call(DEMO_OWNER, get(ROUTE + "/by-version/missing-version/readiness"), null).andExpect(status().isNotFound());
        call(DEMO_OWNER, get("/projects/" + otherProject + "/http-profiles/by-version/"
            + profile.path("profileVersionId").asText() + "/readiness"), null).andExpect(status().isNotFound());
        jdbc.update("update project set deleted_yn='Y' where project_id=?", otherProject);
        call(DEMO_OWNER, get("/projects/" + otherProject + "/http-profiles/by-version/"
            + profile.path("profileVersionId").asText() + "/readiness"), null).andExpect(status().isNotFound());
    }

    @Test
    void everyStaticBlockerIsReturnedInStableOrderEvenForRetiredVersions() throws Exception {
        register("registered", DEMO_PROJECT, "FLOWMAT_TEST_READINESS_STATIC_ONLY_TOKEN");
        JsonNode profile = create(BODY.replace("\"credentialAlias\":null", "\"credentialAlias\":\"registered\""));
        call(DEMO_OWNER, delete(ROUTE + "/" + profile.path("profileId").asText()).param("expectedVersion", "0"), null)
            .andExpect(status().isOk());
        properties.setEnabled(false);
        properties.setAllowedOrigins(List.of());
        properties.setCredentials(Map.of());

        JsonNode result = data(call(DEMO_OWNER, get(path(profile)), null));

        assertFalse(result.path("configurationReady").asBoolean());
        assertEquals(json.readTree("[\"CONNECTOR_DISABLED\",\"PROFILE_RETIRED\","
            + "\"TARGET_POLICY_REJECTED\",\"CREDENTIAL_CONFIGURATION_UNAVAILABLE\"]"), result.path("blockers"));
        assertFalse(result.path("credentialValuesChecked").asBoolean());
        assertFalse(result.path("connectionTested").asBoolean());
        assertFalse(result.toString().contains("registered"));
    }

    @Test
    void anOldVersionIsCheckedAgainstItsOwnConfigurationAndTheCurrentKillSwitch() throws Exception {
        JsonNode original = create(BODY);
        String profileId = original.path("profileId").asText();
        String nextOrigin = "https://next-readiness.example.test";
        properties.setAllowedOrigins(List.of(ORIGIN, nextOrigin));
        JsonNode current = data(call(DEMO_OWNER, put(ROUTE + "/" + profileId),
            "{\"expectedVersion\":0," + BODY.replace(ORIGIN, nextOrigin).substring(1)));
        properties.setAllowedOrigins(List.of(nextOrigin));

        JsonNode oldCheck = data(call(DEMO_OWNER, get(path(original)), null));
        assertEquals(json.readTree("[\"TARGET_POLICY_REJECTED\"]"), oldCheck.path("blockers"));
        assertTrue(data(call(DEMO_OWNER, get(path(current)), null)).path("configurationReady").asBoolean());

        call(DEMO_OWNER, patch(ROUTE + "/" + profileId + "/status"), "{\"expectedVersion\":1,\"enabled\":false}")
            .andExpect(status().isOk());
        assertEquals(json.readTree("[\"PROFILE_DISABLED\"]"),
            data(call(DEMO_OWNER, get(path(current)), null)).path("blockers"));
    }

    @Test
    void foreignAndMissingCredentialAliasesProduceTheSameSafeBlocker() throws Exception {
        register("registered", DEMO_PROJECT, "FLOWMAT_TEST_READINESS_STATIC_ONLY_TOKEN");
        JsonNode profile = create(BODY.replace("\"credentialAlias\":null", "\"credentialAlias\":\"registered\""));
        register("registered", "another-project-sensitive-name", "OTHER_PROJECT_SENSITIVE_ENV");
        JsonNode foreign = data(call(DEMO_OWNER, get(path(profile)), null));
        properties.setCredentials(Map.of());
        JsonNode missing = data(call(DEMO_OWNER, get(path(profile)), null));

        assertEquals(foreign, missing);
        assertEquals(json.readTree("[\"CREDENTIAL_CONFIGURATION_UNAVAILABLE\"]"), foreign.path("blockers"));
        assertFalse(foreign.toString().contains("another-project-sensitive-name"));
        assertFalse(foreign.toString().contains("OTHER_PROJECT_SENSITIVE_ENV"));
    }

    @ParameterizedTest
    @MethodSource("invalidPrivateNetworks")
    void invalidPrivateNetworkConfigurationCannotAppearReady(List<String> configured) throws Exception {
        JsonNode profile = create(BODY);
        properties.setPrivateCidrs(configured);

        JsonNode result = data(call(DEMO_OWNER, get(path(profile)), null));

        assertFalse(result.path("configurationReady").asBoolean());
        assertEquals(json.readTree("[\"TARGET_POLICY_REJECTED\"]"), result.path("blockers"));
        assertFalse(result.path("credentialValuesChecked").asBoolean());
        assertFalse(result.path("connectionTested").asBoolean());
        assertFalse(result.toString().contains("private.internal"));
    }

    private static Stream<Arguments> invalidPrivateNetworks() {
        return Stream.of(
            Arguments.of((Object) null),
            Arguments.of(List.of("not-a-cidr")),
            Arguments.of(List.of("private.internal/24")),
            Arguments.of(Collections.singletonList(null)),
            Arguments.of(List.of("10.0.0.0/33")),
            Arguments.of(List.of("fd00::/129")),
            Arguments.of(Collections.nCopies(65, "10.0.0.0/8")));
    }

    @ParameterizedTest
    @MethodSource("validPrivateNetworks")
    void validPrivateNetworkConfigurationDoesNotRequireAnAddressLookup(List<String> configured) throws Exception {
        JsonNode profile = create(BODY);
        properties.setPrivateCidrs(configured);

        JsonNode result = data(call(DEMO_OWNER, get(path(profile)), null));

        assertTrue(result.path("configurationReady").asBoolean());
        assertTrue(result.path("blockers").isEmpty());
        assertFalse(result.path("credentialValuesChecked").asBoolean());
        assertFalse(result.path("connectionTested").asBoolean());
    }

    private static Stream<Arguments> validPrivateNetworks() {
        return Stream.of(
            Arguments.of(List.of()),
            Arguments.of(List.of("10.77.0.0/16")),
            Arguments.of(List.of("fd12:3456:789a::/48")));
    }

    private JsonNode create(String body) throws Exception {
        return data(call(DEMO_OWNER, post(ROUTE), body));
    }

    private String path(JsonNode profile) {
        return ROUTE + "/by-version/" + profile.path("profileVersionId").asText() + "/readiness";
    }

    private void register(String alias, String projectId, String key) {
        var credential = new ConnectorHttpProperties.Credential();
        credential.setProjectId(projectId);
        credential.setKind(ConnectorHttpProperties.Kind.BEARER);
        credential.setTokenEnvironment(key);
        properties.setCredentials(Map.of(alias, credential));
    }

    private List<Integer> tableCounts() {
        return List.of("connector_profile", "connector_profile_version", "process_execution_binding", "flow_run_http_execution",
            "flow_run_step", "flow_run_step_attempt", "flow_run_event").stream()
            .map(table -> jdbc.queryForObject("select count(*) from " + table, Integer.class)).toList();
    }

    private JsonNode data(ResultActions action) throws Exception {
        return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private String member(String role) {
        String user = "cr-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,created_at) values(?,?,?,?,?,now())",
            UUID.randomUUID().toString(), DEMO_PROJECT, user, role, "active");
        return user;
    }
}
