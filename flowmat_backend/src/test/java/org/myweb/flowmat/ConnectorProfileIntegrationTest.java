package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ConnectorProfileIntegrationTest extends IntegrationTestSupport {
    private static final String ROUTE = "/projects/" + DEMO_PROJECT + "/http-profiles";
    private static final String BODY = "{\"name\":\"API\",\"baseOrigin\":\"https://api.example.com\",\"credentialAlias\":null,"
        + "\"enabled\":true,\"operations\":[{\"operationId\":\"read\",\"method\":\"GET\",\"relativePath\":\"/items\",\"successStatuses\":[200]}]}";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectorProfileQuery profiles;
    @MockitoBean ConnectorTargetPolicy targets;

    @BeforeEach
    void setupPolicy() {
        reset(targets);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsImmutableRevisionsAndRejectsStaleWriters() throws Exception {
        JsonNode created = created();
        String id = created.path("profileId").asText();
        String oldVersion = created.path("profileVersionId").asText();
        assertEquals(0, created.path("version").asLong());
        assertEquals(1, created.path("currentRevision").asInt());
        JsonNode updated = data(call(DEMO_OWNER, put(ROUTE + "/" + id), update(BODY.replace("/items", "/new-items"), 0)));
        assertEquals(1, updated.path("version").asLong());
        assertEquals(2, updated.path("currentRevision").asInt());
        call(DEMO_OWNER, put(ROUTE + "/" + id), update(BODY, 0)).andExpect(status().isConflict());
        assertEquals("/items", profiles.requireOperation(DEMO_PROJECT, oldVersion, "read").relativePath());
        assertEquals("/new-items", profiles.requireOperation(DEMO_PROJECT,
            updated.path("profileVersionId").asText(), "read").relativePath());
        call(DEMO_OWNER, get(ROUTE + "/" + id + "/versions"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        assertEquals(2, jdbc.queryForObject("select count(*) from connector_profile_version where profile_id=?", Integer.class, id));
    }

    @Test
    void stoppingAndDeletingBlockEveryPinnedRevisionWithoutErasingHistory() throws Exception {
        JsonNode created = created();
        String id = created.path("profileId").asText();
        String versionId = created.path("profileVersionId").asText();
        call(DEMO_OWNER, patch(ROUTE + "/" + id + "/status"), "{\"expectedVersion\":0,\"enabled\":false}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertEquals(ErrorCode.CONFLICT, assertThrows(BusinessException.class,
            () -> profiles.requireOperation(DEMO_PROJECT, versionId, "read")).getErrorCode());
        call(DEMO_OWNER, patch(ROUTE + "/" + id + "/status"), "{\"expectedVersion\":1,\"enabled\":true}")
            .andExpect(status().isOk());
        assertEquals("read", profiles.requireOperation(DEMO_PROJECT, versionId, "read").operationId());
        call(DEMO_OWNER, delete(ROUTE + "/" + id).param("expectedVersion", "2"), null).andExpect(status().isOk());
        call(DEMO_OWNER, get(ROUTE + "/" + id), null).andExpect(status().isNotFound());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
            () -> profiles.requireOperation(DEMO_PROJECT, versionId, "read")).getErrorCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from connector_profile_version where profile_id=?", Integer.class, id));
        assertEquals("Y", jdbc.queryForObject("select deleted_yn from connector_profile where profile_id=?", String.class, id));
    }

    @Test
    void onlyOwnerManagesProfilesButMembersCanInspectApprovedOperations() throws Exception {
        JsonNode created = created();
        String id = created.path("profileId").asText();
        for (String role : new String[]{"viewer", "editor"}) {
            String member = member(role);
            call(member, get(ROUTE), null).andExpect(status().isOk());
            call(member, get(ROUTE + "/" + id), null).andExpect(status().isOk());
            call(member, get(ROUTE + "/" + id + "/versions"), null).andExpect(status().isOk());
            call(member, post(ROUTE), BODY).andExpect(status().isForbidden());
            call(member, put(ROUTE + "/" + id), update(BODY, 0)).andExpect(status().isForbidden());
            call(member, patch(ROUTE + "/" + id + "/status"), "{\"expectedVersion\":0,\"enabled\":false}")
                .andExpect(status().isForbidden());
            call(member, delete(ROUTE + "/" + id).param("expectedVersion", "0"), null).andExpect(status().isForbidden());
        }
        call("profile-outsider", get(ROUTE), null).andExpect(status().isForbidden());
        call("profile-outsider", post(ROUTE), "not json").andExpect(status().isForbidden());
    }

    @Test
    void lookupRechecksCurrentDeploymentPolicyAndRejectsProjectMixing() throws Exception {
        JsonNode created = created();
        String versionId = created.path("profileVersionId").asText();
        profiles.requireOperation(DEMO_PROJECT, versionId, "read");
        verify(targets, org.mockito.Mockito.atLeast(2)).validateProfile(DEMO_PROJECT, "https://api.example.com", null);
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Configured target is unavailable."))
            .when(targets).validateProfile(DEMO_PROJECT, "https://api.example.com", null);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
            () -> profiles.requireOperation(DEMO_PROJECT, versionId, "read")).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
            () -> profiles.requireOperation("another-project", versionId, "read")).getErrorCode());
        reset(targets);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
            () -> profiles.requireOperation(DEMO_PROJECT, versionId, "unknown")).getErrorCode());
    }

    @Test
    void pinnedVersionMetadataIsReadableWithoutExecutionApprovalOrCurrentConfiguration() throws Exception {
        JsonNode created = created();
        String profileId = created.path("profileId").asText();
        String versionId = created.path("profileVersionId").asText();
        data(call(DEMO_OWNER, put(ROUTE + "/" + profileId), update(BODY.replace("/items", "/new-items"), 0)));
        data(call(DEMO_OWNER, patch(ROUTE + "/" + profileId + "/status"), "{\"expectedVersion\":1,\"enabled\":false}"));
        String viewer = member("viewer");
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Configured target is unavailable."))
            .when(targets).validateProfile(DEMO_PROJECT, "https://api.example.com", null);
        JsonNode metadata = data(call(viewer, get(ROUTE + "/by-version/" + versionId + "/metadata"), null));
        assertEquals(versionId, metadata.path("profileVersionId").asText());
        assertEquals(profileId, metadata.path("profileId").asText());
        assertEquals(1, metadata.path("revision").asInt());
        assertEquals("https://api.example.com", metadata.path("baseOrigin").asText());
        assertEquals("GET", metadata.path("operations").path(0).path("method").asText());
        assertEquals("/items", metadata.path("operations").path(0).path("relativePath").asText());
        assertEquals(java.util.Set.of("profileVersionId", "profileId", "revision", "baseOrigin", "credentialAlias",
            "operations", "createdBy", "createdAt"),
            metadata.propertyStream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        call(DEMO_OWNER, delete(ROUTE + "/" + profileId).param("expectedVersion", "2"), null).andExpect(status().isOk());
        call(viewer, get(ROUTE + "/by-version/" + versionId + "/metadata"), null).andExpect(status().isOk());
        call("profile-outsider", get(ROUTE + "/by-version/" + versionId + "/metadata"), null).andExpect(status().isForbidden());
    }

    @Test
    void pinnedVersionMetadataCannotCrossProjectsOrReadADeletedParent() throws Exception {
        JsonNode created = created();
        String versionId = created.path("profileVersionId").asText();
        JsonNode other = data(call(DEMO_OWNER, post("/projects"),
            "{\"projectName\":\"Metadata boundary\",\"ownerId\":\"" + DEMO_OWNER + "\"}"));
        String projectId = other.path("projectId").asText();
        call(DEMO_OWNER, get("/projects/" + projectId + "/http-profiles/by-version/" + versionId + "/metadata"), null)
            .andExpect(status().isNotFound());
        call(DEMO_OWNER, get(ROUTE + "/by-version/" + UUID.randomUUID() + "/metadata"), null).andExpect(status().isNotFound());
        call(DEMO_OWNER, get(ROUTE + "/by-version/versions/metadata"), null).andExpect(status().isNotFound());
        JsonNode localProfile = data(call(DEMO_OWNER, post("/projects/" + projectId + "/http-profiles"), BODY));
        jdbc.update("update project set deleted_yn='Y' where project_id=?", projectId);
        call(DEMO_OWNER, get("/projects/" + projectId + "/http-profiles/by-version/"
            + localProfile.path("profileVersionId").asText() + "/metadata"), null).andExpect(status().isNotFound());
    }

    @Test
    void malformedJsonVersionAndUnexpectedCredentialFieldsAreBadRequests() throws Exception {
        call(DEMO_OWNER, post(ROUTE), "{").andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(ROUTE), BODY.replace("{\"name\"", "{\"password\":\"private\",\"name\""))
            .andExpect(status().isBadRequest());
        JsonNode created = created();
        String id = created.path("profileId").asText();
        call(DEMO_OWNER, put(ROUTE + "/" + id), update(BODY, 0).replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"))
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, patch(ROUTE + "/" + id + "/status"), "{\"expectedVersion\":0,\"enabled\":1}")
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, delete(ROUTE + "/" + id).param("expectedVersion", "0.5"), null).andExpect(status().isBadRequest());
    }

    private JsonNode created() throws Exception {
        return data(call(DEMO_OWNER, post(ROUTE), BODY));
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
        String user = "cp-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,created_at) values(?,?,?,?,?,now())",
            UUID.randomUUID().toString(), DEMO_PROJECT, user, role, "active");
        return user;
    }

    private static String update(String body, long version) {
        return "{\"expectedVersion\":" + version + "," + body.substring(1);
    }
}
