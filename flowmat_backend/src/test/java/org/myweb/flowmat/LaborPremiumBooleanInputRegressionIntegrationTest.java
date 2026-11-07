package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A worker's own pay premiums over the project's (docs/architecture/adr/ADR-028-pay-premiums.md decisions 20-22), on real Postgres. */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class LaborPremiumBooleanInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Worker premiums " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        JsonNode zone = data(DEMO_OWNER, get("/projects/" + projectId + "/time-zone"), null);
        data(DEMO_OWNER, put("/projects/" + projectId + "/time-zone"),
            Map.of("timeZone", "Asia/Seoul", "expectedVersion", zone.path("version").asLong()));
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(DEMO_OWNER, delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "restDaysAsHolidays,0", "restDaysAsHolidays,1",
        "restDaysAsHolidays,\"true\"", "restDaysAsHolidays,\"false\"",
        "followProject,0", "followProject,1",
        "followProject,\"true\"", "followProject,\"false\""
    })
    void malformedBooleanCannotChangeSavedRules(String field, String token) throws Exception {
        String path = "/projects/" + projectId + "/workers/" + member() + "/labor-premiums";
        data(DEMO_OWNER, put(path), Map.of("holidayPercent", 50, "reason", "Keep this saved rule"));
        JsonNode before = data(DEMO_OWNER, get(path), null);
        JsonNode body = mapper.readTree("{\"" + field + "\":" + token + "}");
        if (field.equals("restDaysAsHolidays")) ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("holidayPercent", 50);
        int result = call(DEMO_OWNER, put(path), body).andReturn().getResponse().getStatus();
        JsonNode after = data(DEMO_OWNER, get(path), null);
        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(400, result),
            () -> assertEquals(before, after));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "restDaysAsHolidays,true", "restDaysAsHolidays,false", "restDaysAsHolidays,null",
        "followProject,true", "followProject,false", "followProject,null"
    })
    void actualBooleanAndNullRemainAccepted(String field, String token) throws Exception {
        String path = "/projects/" + projectId + "/workers/" + member() + "/labor-premiums";
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("{\"" + field + "\":" + token + "}");
        if (field.equals("restDaysAsHolidays")) body.put("holidayPercent", 50);
        data(DEMO_OWNER, put(path), body);
    }

    @Test
    void omittedBooleansRemainAccepted() throws Exception {
        data(DEMO_OWNER, put("/projects/" + projectId + "/workers/" + member() + "/labor-premiums"), Map.of());
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, "editor");
        return id;
    }

    private JsonNode data(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(user, request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
