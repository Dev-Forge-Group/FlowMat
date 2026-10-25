package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class JsonMappingPreviewIntegrationTest extends IntegrationTestSupport {
    private static final String SIMPLE = """
        {"version":1,"source":{"name":"Example site"},"mapping":{"object":{"siteName":{"from":"/name"}}}}
        """;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;

    @Test
    void mapsExactNumbersArraysNullAndDatesWithoutCreatingRunsOrChangingTheProject() throws Exception {
        Fixture f = new Fixture();
        var before = f.stored();
        String body = """
            {"version":1,"source":{"site":{"name":"Example site"},
              "quantity":9007199254740993.123456789,"when":"2026-10-23T08:30:00.123456789+09:00",
              "coords":[[1.25,2.5],[3,4]],"empty":null},
             "mapping":{"object":{
              "quantity":{"from":"/quantity"},"unit":{"literal":"kg"},
              "attrs":{"object":{"site":{"from":"/site/name"},"when":{"from":"/when"},
                "coords":{"from":"/coords"}}},
              "optional":{"from":"/missing","required":false},"presentNull":{"from":"/empty"}}}}
            """;

        JsonNode data = f.preview(DEMO_OWNER, body, 200).path("data");

        assertEquals(1, data.path("version").asInt());
        JsonNode result = data.path("result");
        assertEquals(new BigDecimal("9007199254740993.123456789"), result.path("quantity").decimalValue());
        assertEquals("kg", result.path("unit").asText());
        assertEquals("Example site", result.path("attrs").path("site").asText());
        assertEquals("2026-10-23T08:30:00.123456789+09:00", result.path("attrs").path("when").asText());
        assertEquals(new BigDecimal("1.25"), result.path("attrs").path("coords").get(0).get(0).decimalValue());
        assertFalse(result.has("optional"));
        assertTrue(result.has("presentNull"));
        assertTrue(result.path("presentNull").isNull());
        assertEquals(before, f.stored());
    }

    @ParameterizedTest
    @CsvSource({"owner,200", "editor,200", "viewer,403", "outsider,403", "revoked,403", "anonymous,401"})
    void usesCurrentProjectWriteAccess(String role, int expected) throws Exception {
        Fixture f = new Fixture();
        String actor = role.equals("owner") ? DEMO_OWNER : role.equals("anonymous") ? null
            : f.member(role.equals("revoked") ? "editor" : role);
        if (role.equals("revoked")) {
            f.preview(actor, SIMPLE, 200);
            jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, actor);
        }
        var before = f.stored();

        f.preview(actor, SIMPLE, expected);

        assertEquals(before, f.stored());
    }

    @Test
    void aDeletedProjectIsNotFoundAndAccessIsCheckedBeforeMapping() throws Exception {
        Fixture f = new Fixture();
        f.preview(f.member("outsider"), "not JSON", 403);
        jdbc.update("update project set deleted_yn='Y' where project_id=?", f.project);
        f.preview(DEMO_OWNER, "not JSON", 404);
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "{", "null", "[]", "{}",
            "{\"version\":2,\"source\":{},\"mapping\":{\"literal\":1}}",
            "{\"version\":1.5,\"source\":{},\"mapping\":{\"literal\":1}}",
            "{\"version\":true,\"source\":{},\"mapping\":{\"literal\":1}}",
            "{\"version\":1,\"mapping\":{\"literal\":1}}",
            "{\"version\":1,\"source\":{}}",
            "{\"version\":1,\"source\":{},\"mapping\":null}",
            "{\"version\":1,\"source\":{},\"mapping\":{\"literal\":1},\"url\":\"https://unused.invalid\"}",
            "{\"version\":1,\"version\":1,\"source\":{},\"mapping\":{\"literal\":1}}",
            "{\"version\":1,\"source\":{\"same\":1,\"same\":2},\"mapping\":{\"from\":\"\"}}",
            "{\"version\":1,\"source\":1e100000000,\"mapping\":{\"from\":\"\"}}",
            "{\"version\":1,\"source\":{},\"mapping\":{\"literal\":1,\"from\":\"\"}}",
            "{\"version\":1,\"source\":{},\"mapping\":{\"from\":\"/missing\"}}",
            "{\"version\":1,\"source\":{},\"mapping\":{\"from\":\"/missing\",\"required\":0}}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void malformedOrUnsupportedSpecificationsAre400(String body) throws Exception {
        Fixture f = new Fixture();
        JsonNode rejected = f.preview(DEMO_OWNER, body, 400);
        assertFalse(rejected.path("success").asBoolean());
        assertTrue(rejected.path("message").isTextual());
    }

    @Test
    void requestLimitCountsUtf8BytesAndMalformedErrorsDoNotEchoCallerData() throws Exception {
        Fixture f = new Fixture();
        String marker = "private-example-marker";
        String body = "{\"version\":1,\"source\":\"" + marker + "가".repeat(44000)
            + "\",\"mapping\":{\"from\":\"\"}}";
        assertTrue(body.length() < 131072);
        JsonNode rejected = f.preview(DEMO_OWNER, body, 400);
        assertFalse(rejected.toString().contains(marker));
        JsonNode malformed = f.preview(DEMO_OWNER, "{\"" + marker + "\":", 400);
        assertFalse(malformed.toString().contains(marker));
    }

    @Test
    void missingSourceAndExplicitNullRemainDifferent() throws Exception {
        Fixture f = new Fixture();
        JsonNode data = f.preview(DEMO_OWNER,
            "{\"version\":1,\"source\":null,\"mapping\":{\"from\":\"\"}}", 200).path("data");
        assertTrue(data.has("result"));
        assertTrue(data.path("result").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/x-www-form-urlencoded", "multipart/form-data", "text/plain"})
    void rejectsNonJsonMediaBeforeAnyFormParameterParsing(String contentType) throws Exception {
        Fixture f = new Fixture();
        mvc.perform(post("/projects/" + f.project + "/json-mapping/preview")
                .header("Authorization", bearer(DEMO_OWNER)).contentType(contentType).content(SIMPLE))
            .andExpect(status().isUnsupportedMediaType());
    }

    private class Fixture {
        final String project;

        Fixture() throws Exception {
            String response = mvc.perform(post("/projects").header("Authorization", bearer(DEMO_OWNER))
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of(
                        "projectName", "Mapping preview " + UUID.randomUUID(), "ownerId", DEMO_OWNER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            project = mapper.readTree(response).path("data").path("projectId").asText();
        }

        JsonNode preview(String actor, String body, int expected) throws Exception {
            MockHttpServletRequestBuilder request = post("/projects/" + project + "/json-mapping/preview")
                .contentType(MediaType.APPLICATION_JSON).content(body);
            if (actor != null) request.header("Authorization", bearer(actor));
            String response = mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
            return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response);
        }

        String member(String role) {
            String id = "jm-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
            if (!role.equals("outsider")) jdbc.update(
                "insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, id, role);
            return id;
        }

        Map<String, Object> stored() {
            return Map.of("project", jdbc.queryForList("select * from project where project_id=?", project),
                "runs", jdbc.queryForObject("select count(*) from flow_run where project_id=?", Long.class, project),
                "revisions", jdbc.queryForObject("select count(*) from workflow_revision", Long.class));
        }
    }

    private String bearer(String user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
