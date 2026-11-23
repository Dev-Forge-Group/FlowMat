package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** NT3/NT4: the bounded list does not bound unread counts or allow hidden notifications to be changed. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationWindowIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anUnreadNotificationOlderThanTheLatestHundredStillCountsAndCanBeMarkedRead() throws Exception {
        Fixture f = fixture();
        String old = notification(f.user(), f.project(), false, 200);
        for (int i = 0; i < 100; i++) {
            notification(f.user(), f.project(), true, i);
        }
        call(f.user(), get("/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(100))
            .andExpect(jsonPath("$.data[?(@.read == false)]").isEmpty());
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(1));
        call(f.user(), post("/notifications/" + old + "/read")).andExpect(status().isOk());
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(0));
    }

    @Test
    void hiddenRecentRowsCannotHideTheCountOrBeChangedByBulkReading() throws Exception {
        Fixture f = fixture();
        String accessible = notification(f.user(), f.project(), false, 200);
        // Real project, but this person has no access; its notification rows must remain private and unread.
        for (int i = 0; i < 101; i++) {
            notification(f.user(), DEMO_PROJECT, false, i);
        }
        call(f.user(), get("/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(1));
        call(f.user(), post("/notifications/read-all")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marked").value(1));
        assertEquals(true, jdbc.queryForObject("select is_read from notification where nfc_id=?", Boolean.class, accessible));
        assertEquals(101, jdbc.queryForObject("select count(*) from notification where user_id=? and project_id=? and not is_read",
            Integer.class, f.user(), DEMO_PROJECT));
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(0));
    }

    private Fixture fixture() throws Exception {
        String user = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        var response = mvc.perform(post("/projects").header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "projectName", "Notification window " + UUID.randomUUID(), "ownerId", DEMO_OWNER))))
            .andExpect(status().isOk()).andReturn().getResponse();
        String project = json.readTree(response.getContentAsString()).path("data").path("projectId").asText();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'viewer')",
            UUID.randomUUID().toString(), project, user);
        return new Fixture(user, project);
    }

    private String notification(String user, String project, boolean read, int ageMinutes) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into notification(nfc_id,user_id,project_id,nfc_type,nfc_title,nfc_body,is_read,created_at) "
            + "values(?,?,?,'stock_alert','Window fixture','Private item and location',?,now() - (? * interval '1 minute'))",
            id, user, project, read, ageMinutes);
        return id;
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.header("Authorization", "Bearer " + jwt.generateAccessToken(user)));
    }

    private record Fixture(String user, String project) { }
}
