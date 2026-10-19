package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Invalid raw reason text must not disappear during trimming or be replaced while stored. */
@AutoConfigureMockMvc
class ScheduleVersionReasonInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    static Stream<Arguments> invalidReasons() {
        return Stream.of("worker", "copy", "equipment", "clear").flatMap(route -> Stream.of(
            "bad\\u0000reason", "\\u0000reason", "bad\\ud800reason", "bad\\udc00reason")
            .map(value -> Arguments.of(route, value)));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidReasons")
    void invalidCharactersReturn400WithoutChangingAnyVersion(String route, String escaped) throws Exception {
        Fixture fixture = new Fixture();
        JsonNode before = fixture.state();
        fixture.send(route, "\"" + escaped + "\"")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", Matchers.containsString("reason")));
        assertEquals(before, fixture.state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "copy", "equipment", "clear"})
    void validUnicodeIsPreservedAndOnlyOuterWhitespaceIsTrimmed(String route) throws Exception {
        Fixture fixture = new Fixture();
        String text = "  Shift changed \ud55c\uae00 \ud83d\udd25  ";
        fixture.send(route, mapper.writeValueAsString(text)).andExpect(status().isOk());
        JsonNode stored = "worker".equals(route) ? fixture.worker(DEMO_OWNER)
            : "copy".equals(route) ? fixture.worker(fixture.target)
            : fixture.equipment();
        assertTrue(stored.findValuesAsText("reason").contains(text.trim()), stored.toString());
    }

    private final class Fixture {
        final String project;
        final String target;
        final String equipmentId;

        Fixture() throws Exception {
            project = data(post("/projects"), Map.of("projectName", "Reason text " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
                .path("projectId").asText();
            target = "reason-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", target, target + "@test.local", DEMO_OWNER);
            jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'editor')",
                UUID.randomUUID().toString(), project, target);
            data(put(base(DEMO_OWNER) + "/shifts"), Map.of("shifts", shifts(), "reason", "Source reason"));
            data(put(base(target) + "/shifts"), Map.of("shifts", shifts(), "reason", "Target reason"));
            equipmentId = data(post("/equipments"), Map.of("projectId", project, "equipmentCode", "NOTE-" + UUID.randomUUID(),
                "equipmentName", "Reason fixture", "equipmentType", "machine" )).path("equipmentId").asText();
            data(put("/equipments/" + equipmentId + "/calendar"), Map.of("shifts", shifts(), "reason", "Equipment reason"));
        }

        String base(String user) { return "/projects/" + project + "/workers/" + user; }

        ResultActions send(String route, String reasonJson) throws Exception {
            if ("clear".equals(route)) {
                return call(delete("/equipments/" + equipmentId + "/calendar").param("reason", mapper.readTree(reasonJson).asText()), null);
            }
            String path = "worker".equals(route) ? base(DEMO_OWNER) + "/shifts"
                : "copy".equals(route) ? base(DEMO_OWNER) + "/shifts/copy" : "/equipments/" + equipmentId + "/calendar";
            String body = "copy".equals(route)
                ? "{\"targets\":[{\"userId\":\"" + target + "\",\"offsetWeeks\":0}],\"reason\":" + reasonJson + "}"
                : "{\"shifts\":[{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\",\"workDays\":[1,2,3,4,5]}],\"reason\":" + reasonJson + "}";
            return mvc.perform(("copy".equals(route) ? post(path) : put(path))
                .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        JsonNode worker(String user) throws Exception { return data(get(base(user)), null); }
        JsonNode equipment() throws Exception { return data(get("/equipments/" + equipmentId + "/schedule"), null); }

        JsonNode state() throws Exception {
            ObjectNode result = mapper.createObjectNode();
            result.set("source", worker(DEMO_OWNER));
            result.set("target", worker(target));
            result.set("equipment", equipment());
            return result;
        }
    }

    private List<Map<String, Object>> shifts() {
        return List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5)));
    }

    private String bearer() { return "Bearer " + jwt.generateAccessToken(DEMO_OWNER); }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request.header("Authorization", bearer()));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        assertEquals(200, response.getStatus(), request.toString());
        return mapper.readTree(response.getContentAsString()).path("data");
    }
}
