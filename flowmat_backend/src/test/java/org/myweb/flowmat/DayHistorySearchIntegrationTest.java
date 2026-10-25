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
import java.util.ArrayList;
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

/** The day histories (2fe) beyond the latest 100, page by page with the version history search's filters, on real Postgres. */
@AutoConfigureMockMvc
class DayHistorySearchIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aWorkersDateHistoryIsReadPageByPageAndFiltered() throws Exception {
        String kim = member();
        String other = member();
        String base = "/projects/" + DEMO_PROJECT + "/workers/";
        for (String worker : List.of(kim, other)) {
            data(call(DEMO_OWNER, put(base + worker + "/shifts"), Map.of("shifts", List.of(Map.of("shiftStart", "09:00",
                "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))));
        }
        data(call(DEMO_OWNER, put(base + kim + "/days/2041-09-02"), Map.of("shifts", List.of(), "reason", "One")));
        data(call(DEMO_OWNER, put(base + kim + "/days/2041-09-03"), Map.of("shifts", List.of(), "reason", "Two")));
        data(call(DEMO_OWNER, delete(base + kim + "/days/2041-09-02"), null));
        data(call(DEMO_OWNER, post(base + kim + "/days-off"), Map.of("firstDay", "2041-10-01", "reason", "Off")));
        data(call(DEMO_OWNER, put(base + other + "/days/2041-09-02"), Map.of("shifts", List.of(), "reason", "Other")));
        String search = base + kim + "/day-history/search";

        // Two at a time, newest first, then the rest after the cursor.
        JsonNode first = data(call(DEMO_OWNER, get(search).param("limit", "2"), null));
        assertEquals(List.of("day_off", "day"), kinds(first));
        assertEquals("removed", first.path("entries").get(1).path("action").asText());
        String cursor = first.path("nextCursor").asText();
        JsonNode rest = data(call(DEMO_OWNER, get(search).param("limit", "2").param("cursor", cursor), null));
        assertEquals(List.of("Two", "One"), reasons(rest));
        assertTrue(rest.path("nextCursor").isNull(), rest.toString());

        assertEquals(List.of("day_off"), kinds(data(call(DEMO_OWNER, get(search).param("kind", "day_off"), null))));
        assertEquals(1, data(call(DEMO_OWNER, get(search).param("action", "removed"), null)).path("entries").size());
        call(DEMO_OWNER, get(search).param("kind", "downtime"), null).andExpect(status().isBadRequest());
        // Another worker's entry is not a cursor here; readers read, outsiders do not.
        String others = data(call(DEMO_OWNER, get(base + other + "/day-history"), null)).get(0).path("historyId").asText();
        call(DEMO_OWNER, get(search).param("cursor", others), null).andExpect(status().isBadRequest());
        call("unrelated-user", get(search), null).andExpect(status().isForbidden());
    }

    @Test
    void anEquipmentsDateAndDowntimeHistoryIsReadPageByPage() throws Exception {
        String press = data(call(DEMO_OWNER, post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentName",
            "Search " + UUID.randomUUID(), "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))).path("equipmentId").asText();
        String base = "/equipments/" + press;
        data(call(DEMO_OWNER, put(base + "/calendar"), Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))));
        data(call(DEMO_OWNER, put(base + "/days/2041-09-02"), Map.of("shifts", List.of(), "reason", "Closed")));
        data(call(DEMO_OWNER, post(base + "/downtimes"), Map.of("downtimeType", "breakdown", "startsAt", "2041-09-05T08:00:00Z",
            "endsAt", "2041-09-05T09:00:00Z", "reason", "Belt")));
        JsonNode first = data(call(DEMO_OWNER, get(base + "/day-history/search").param("limit", "1"), null));
        assertEquals(List.of("downtime"), kinds(first));
        JsonNode rest = data(call(DEMO_OWNER, get(base + "/day-history/search").param("cursor", first.path("nextCursor").asText()), null));
        assertEquals(List.of("Closed"), reasons(rest));
        assertEquals(List.of("day"), kinds(data(call(DEMO_OWNER, get(base + "/day-history/search").param("kind", "day"), null))));
        call(DEMO_OWNER, get(base + "/day-history/search").param("kind", "day_off"), null).andExpect(status().isBadRequest());
        call("unrelated-user", get(base + "/day-history/search"), null).andExpect(status().isForbidden());
    }

    private static List<String> kinds(JsonNode page) {
        List<String> kinds = new ArrayList<>();
        page.path("entries").forEach(entry -> kinds.add(entry.path("kind").asText()));
        return kinds;
    }

    private static List<String> reasons(JsonNode page) {
        List<String> reasons = new ArrayList<>();
        page.path("entries").forEach(entry -> reasons.add(entry.path("reason").asText()));
        return reasons;
    }

    /** A new editor of the demo project. */
    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private JsonNode data(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
