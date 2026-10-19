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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Why a worker's shifts or an equipment calendar version was saved (docs/architecture/adr/ADR-017-version-reasons.md, V95). */
@AutoConfigureMockMvc
class VersionReasonIntegrationTest extends IntegrationTestSupport {
    private static final String LONG = "x".repeat(201);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aWorkersShiftVersionsKeepTheReasonLastGivenForThem() throws Exception {
        String kim = member();
        String lee = member();
        String workers = "/projects/" + DEMO_PROJECT + "/workers/";
        JsonNode first = data(put(workers + kim + "/shifts"), shifts(null, "  Day shift for the new line  "));
        JsonNode now = first.path("schedules").get(0);
        assertEquals("Day shift for the new line", now.path("reason").asText());
        assertEquals(DEMO_OWNER, now.path("updatedBy").asText());
        assertTrue(now.hasNonNull("updatedAt"));

        JsonNode later = data(put(workers + kim + "/shifts"), shifts("2041-09-09", "Night shifts from September"));
        JsonNode next = later.path("schedules").get(1);
        assertEquals("Night shifts from September", next.path("reason").asText());
        String nextId = next.path("scheduleId").asText();
        assertEquals("Night shifts from September",
            data(get(workers + kim + "/schedules/" + nextId), null).path("reason").asText());
        // Saving the same date again replaces the reason too; without one there is none.
        JsonNode again = data(put(workers + kim + "/shifts"), shifts("2041-09-09", null));
        assertTrue(again.path("schedules").get(1).path("reason").isNull());

        call(put(workers + kim + "/shifts"), shifts(null, LONG)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason must be at most 200 characters."));
        // Text PostgreSQL cannot store is refused before anything is saved: a NUL, or half a UTF-16 pair. The half pair goes as a
        // JSON escape, since encoding the body as UTF-8 would turn it into "?".
        call(put(workers + kim + "/shifts"), shifts("2041-10-07", "Night\u0000shift")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason contains a character PostgreSQL cannot store."));
        mockMvc.perform(put(workers + kim + "/shifts").header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shifts\":[],\"effectiveFrom\":\"2041-10-07\",\"reason\":\"Night \\uD83D\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason contains a character PostgreSQL cannot store."));
        assertEquals(2, data(get(workers + kim), null).path("schedules").size());

        // A team copy gives every copy the same reason.
        Map<String, Object> copy = Map.of("targets", List.of(Map.of("userId", lee)), "reason", "Same as kim");
        JsonNode copied = data(post(workers + kim + "/shifts/copy"), copy).get(0);
        assertEquals("Same as kim", copied.path("schedules").get(0).path("reason").asText());
    }

    @Test
    void anEquipmentCalendarVersionKeepsItsReasonAlsoWhenItIsCleared() throws Exception {
        String code = "WHY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String press = data(post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine")).path("equipmentId").asText();
        Map<String, Object> calendar = new HashMap<>(Map.of("shiftStart", "08:00", "shiftEnd", "16:00", "workDays", List.of(1, 2, 3, 4, 5),
            "reason", "Two shifts until the new press"));
        JsonNode saved = data(put("/equipments/" + press + "/calendar"), calendar);
        assertEquals("Two shifts until the new press", saved.path("versions").get(0).path("reason").asText());
        assertEquals(DEMO_OWNER, saved.path("versions").get(0).path("updatedBy").asText());

        JsonNode cleared = data(delete("/equipments/" + press + "/calendar").param("effectiveFrom", "2041-09-16")
            .param("reason", "Runs around the clock after the upgrade"), null);
        JsonNode off = cleared.path("versions").get(1);
        assertEquals("Runs around the clock after the upgrade", off.path("reason").asText());
        JsonNode version = data(get("/equipments/" + press + "/calendar/versions/" + off.path("scheduleId").asText()), null);
        assertEquals("Runs around the clock after the upgrade", version.path("reason").asText());
        assertTrue(version.hasNonNull("updatedAt"));

        calendar.put("reason", LONG);
        call(put("/equipments/" + press + "/calendar"), calendar).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason can be at most 200 characters."));
        calendar.put("reason", "Two\u0000shifts");
        call(put("/equipments/" + press + "/calendar"), calendar).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason contains a character PostgreSQL cannot store."));
        call(delete("/equipments/" + press + "/calendar").param("effectiveFrom", "2041-10-07").param("reason", "Off \uDC00"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("reason contains a character PostgreSQL cannot store."));
        assertEquals(2, data(get("/equipments/" + press + "/schedule"), null).path("versions").size());
    }

    /** Monday to Friday 09:00-17:00, from {@code effectiveFrom} (today when null), with a reason when given. */
    private static Map<String, Object> shifts(String effectiveFrom, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))));
        if (effectiveFrom != null) {
            body.put("effectiveFrom", effectiveFrom);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    /** A new editor of the demo project, with no shifts yet. */
    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
