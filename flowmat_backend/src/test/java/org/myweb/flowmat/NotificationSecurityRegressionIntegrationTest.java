package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** NT3/NT4: notification content and read commands respect the recipient's current access. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationSecurityRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationCommand notifications;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entityManager;

    @Test
    void anonymousRequestsCannotListCountOrMarkNotifications() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications/missing/read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersNotificationCannotBeReadEvenByTheProjectOwner() throws Exception {
        Fixture f = fixture("viewer");
        String id = notification(f.user(), f.project());
        call(DEMO_OWNER, get("/notifications").param("userId", f.user()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.notificationId == '" + id + "')]").isEmpty());
        call(DEMO_OWNER, post("/notifications/" + id + "/read")).andExpect(status().isNotFound());
        call(f.user(), post("/notifications/missing/read")).andExpect(status().isNotFound());
        unchanged(id);
    }

    @Test
    void aViewerCanReadTheirOwnNotificationAndRepeatedReadingKeepsTheTimestamp() throws Exception {
        Fixture f = fixture("viewer");
        String id = notification(f.user(), f.project());
        call(f.user(), post("/notifications/" + id + "/read")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.read").value(true));
        Object first = jdbc.queryForObject("select read_at from notification where nfc_id=?", Object.class, id);
        call(f.user(), post("/notifications/" + id + "/read")).andExpect(status().isOk());
        assertEquals(first, jdbc.queryForObject("select read_at from notification where nfc_id=?", Object.class, id));
        call(f.user(), get("/notifications").param("unreadOnly", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest(name = "membership {0} hides the body, count and read command")
    @ValueSource(strings = {"removed", "invited"})
    void theSameTokenLosesAccessWhenMembershipIsNoLongerActive(String memberStatus) throws Exception {
        Fixture f = fixture("editor");
        String id = notification(f.user(), f.project());
        String token = jwt.generateAccessToken(f.user());
        request(token, get("/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].body").value("Private action · From SECRET-DOCK to SECRET-BIN."));
        jdbc.update("update project_member set member_status=? where project_id=? and user_id=?", memberStatus, f.project(), f.user());
        hidden(token, id);
        unchanged(id);
    }

    @Test
    void aDeletedProjectHidesNotificationsEvenFromItsOwner() throws Exception {
        Fixture f = fixture("viewer");
        String id = notification(f.user(), f.project());
        jdbc.update("update project set owner_id=?, deleted_yn='Y' where project_id=?", f.user(), f.project());
        hidden(jwt.generateAccessToken(f.user()), id);
        unchanged(id);
    }

    @Test
    void bulkReadDoesNotRevealOrModifyUnreadNotificationsOfProjectsTheUserLeft() throws Exception {
        Fixture f = fixture("editor");
        String first = notification(f.user(), f.project());
        String second = notification(f.user(), f.project());
        jdbc.update("update project_member set member_status='removed' where project_id=? and user_id=?", f.project(), f.user());
        call(f.user(), post("/notifications/read-all")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marked").value(0));
        unchanged(first);
        unchanged(second);
    }

    @Test
    void bulkReadChangesOnlyVisibleNotificationsAndLeavesOtherUsersRowsAlone() throws Exception {
        Fixture f = fixture("viewer");
        String visible = notification(f.user(), f.project());
        String hidden = notification(f.user(), project());
        String other = notification(user(), f.project());
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(1));
        call(f.user(), post("/notifications/read-all")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marked").value(1));
        assertEquals(true, jdbc.queryForObject("select is_read from notification where nfc_id=?", Boolean.class, visible));
        unchanged(hidden);
        unchanged(other);
        call(f.user(), post("/notifications/read-all")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marked").value(0));
    }

    @Test
    void aNotificationWithoutAProjectRemainsVisibleAndCanBeMarkedRead() throws Exception {
        String user = user();
        String id = notification(user, null);
        call(user, get("/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].notificationId").value(id));
        call(user, get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(1));
        call(user, post("/notifications/read-all")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.marked").value(1));
        assertEquals(true, jdbc.queryForObject("select is_read from notification where nfc_id=?", Boolean.class, id));
    }

    @Test
    void anAssignmentNotificationRollsBackWithItsEnclosingTransaction() throws Exception {
        Fixture f = fixture("editor");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            notifications.notifyAssignee(f.user(), DEMO_OWNER, f.project(), "ncr_action", "Rollback work",
                "Private action", "nonconformity", "rollback-ref");
            entityManager.flush();
            assertEquals(1, jdbc.queryForObject("select count(*) from notification where user_id=?", Integer.class, f.user()));
            tx.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("select count(*) from notification where user_id=?", Integer.class, f.user()));
        call(f.user(), get("/notifications/unread-count")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unread").value(0));
    }

    private void hidden(String token, String id) throws Exception {
        request(token, get("/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        request(token, get("/notifications/unread-count")).andExpect(status().isOk()).andExpect(jsonPath("$.data.unread").value(0));
        request(token, post("/notifications/" + id + "/read")).andExpect(status().isNotFound());
    }

    private void unchanged(String id) {
        assertFalse(jdbc.queryForObject("select is_read from notification where nfc_id=?", Boolean.class, id));
        assertNull(jdbc.queryForObject("select read_at from notification where nfc_id=?", Object.class, id));
    }

    private Fixture fixture(String role) throws Exception {
        String user = user();
        String project = project();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), project, user, role);
        return new Fixture(project, user);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private String project() throws Exception {
        var response = mvc.perform(post("/projects").header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("projectName", "Notification security " + UUID.randomUUID(), "ownerId", DEMO_OWNER))))
            .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("projectId").asText();
    }

    private String notification(String user, String project) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into notification(nfc_id,user_id,project_id,nfc_type,nfc_title,nfc_body,ref_type,ref_id,is_read,created_at) "
            + "values(?,?,?,'ncr_action','Private work','Private action · From SECRET-DOCK to SECRET-BIN.','nonconformity','private-ref',false,now())",
            id, user, project);
        return id;
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder builder) throws Exception {
        return request(jwt.generateAccessToken(user), builder);
    }

    private ResultActions request(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private record Fixture(String project, String user) { }
}
