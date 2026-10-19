package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Equipment calendar history retains project permissions and binds every version to its equipment (ADR-014). */
@AutoConfigureMockMvc
class EquipmentCalendarVersionAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private ProjectMemberRepository members;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0} cannot {1} a calendar version")
    @CsvSource({"anonymous,read,401", "anonymous,delete,401", "outsider,read,403", "outsider,delete,403", "viewer,delete,403"})
    void refusedActorsCannotReadOrChangeCalendarVersions(String role, String operation, int expected) throws Exception {
        Calendar calendar = new Calendar();
        String actor = "anonymous".equals(role) ? null : "ea-" + UUID.randomUUID();
        if ("viewer".equals(role)) member(calendar.project, actor, role);
        var before = stored(calendar);

        call(actor, command(calendar.path(), operation), null).andExpect(status().is(expected));

        assertEquals(before, stored(calendar), "Refused requests must retain versions, shifts and their audit fields");
    }

    @ParameterizedTest(name = "{1} binds a version to its {0}")
    @CsvSource({"equipment,read", "equipment,delete", "project,read", "project,delete"})
    void authorizedEquipmentPathsCannotUseAnotherVersionsId(String scope, String operation) throws Exception {
        Calendar first = new Calendar();
        Calendar other = "equipment".equals(scope) ? new Calendar(first.project) : new Calendar();
        var firstBefore = stored(first);
        var otherBefore = stored(other);
        String wrong = "/equipments/" + other.equipment + "/calendar/versions/" + first.version;

        call(DEMO_OWNER, command(wrong, operation), null).andExpect(status().isNotFound());

        assertEquals(firstBefore, stored(first));
        assertEquals(otherBefore, stored(other));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor"})
    void removedMembershipRevokesHistoryAndDeleteWithTheExistingToken(String role) throws Exception {
        Calendar calendar = new Calendar();
        String actor = "er-" + UUID.randomUUID();
        ProjectMember member = member(calendar.project, actor, role);
        String token = jwt.generateAccessToken(actor);
        var before = stored(calendar);
        mvc.perform(get(calendar.path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        member.setMemberStatus("removed");
        members.saveAndFlush(member);

        mvc.perform(get(calendar.path()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(delete(calendar.path()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        assertEquals(before, stored(calendar));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "editor"})
    void writersCanDeleteOnlyAFutureVersionAndKeepTheEarlierCalendar(String role) throws Exception {
        Calendar calendar = new Calendar();
        String actor = DEMO_OWNER;
        if ("editor".equals(role)) {
            actor = "ew-" + UUID.randomUUID();
            member(calendar.project, actor, role);
        }
        JsonNode before = data(actor, get("/equipments/" + calendar.equipment + "/schedule"), null);
        String current = before.path("versions").get(0).path("scheduleId").asText();
        call(actor, delete("/equipments/" + calendar.equipment + "/calendar/versions/" + current), null)
            .andExpect(status().isConflict());
        assertEquals(before, data(actor, get("/equipments/" + calendar.equipment + "/schedule"), null));

        JsonNode after = data(actor, delete(calendar.path()), null);

        assertEquals(1, after.path("versions").size());
        assertEquals(current, after.path("versions").get(0).path("scheduleId").asText());
        assertEquals(before.path("calendar"), after.path("calendar"));
        call(actor, get(calendar.path()), null).andExpect(status().isNotFound());
    }

    @Test
    void viewerCanReadTheFutureVersionAndItsAuditFields() throws Exception {
        Calendar calendar = new Calendar();
        String viewer = "ev-" + UUID.randomUUID();
        member(calendar.project, viewer, "viewer");

        JsonNode version = data(viewer, get(calendar.path()), null);

        assertEquals(calendar.version, version.path("scheduleId").asText());
        assertEquals("2041-09-09", version.path("effectiveFrom").asText());
        assertEquals("Future maintenance rotation", version.path("reason").asText());
        assertEquals(DEMO_OWNER, version.path("updatedBy").asText());
        assertEquals(40, version.path("weeklyHours").asInt());
        assertFalse(version.path("current").asBoolean());
        assertFalse(version.path("alwaysRunning").asBoolean());
        assertFalse(version.path("updatedAt").asText().isEmpty());
    }

    @Test
    void deletedEquipmentDoesNotExposeOrDeleteItsCalendarHistory() throws Exception {
        Calendar calendar = new Calendar();
        jdbc.update("update equipment set deleted_yn='Y' where equipment_id=?", calendar.equipment);
        var before = stored(calendar);

        call(DEMO_OWNER, get(calendar.path()), null).andExpect(status().isNotFound());
        call(DEMO_OWNER, delete(calendar.path()), null).andExpect(status().isNotFound());

        assertEquals(before, stored(calendar));
    }

    private final class Calendar {
        final String project;
        final String equipment;
        final String version;

        Calendar() throws Exception {
            this(data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Calendar access " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText());
        }

        Calendar(String project) throws Exception {
            this.project = project;
            String code = "ACCESS-" + UUID.randomUUID().toString().substring(0, 8);
            equipment = data(DEMO_OWNER, post("/equipments"), Map.of("projectId", project, "equipmentCode", code,
                "equipmentName", code, "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))
                .path("equipmentId").asText();
            data(DEMO_OWNER, put("/equipments/" + equipment + "/calendar"), Map.of("shiftStart", "08:00", "shiftEnd", "16:00",
                "workDays", List.of(1, 2, 3, 4, 5)));
            JsonNode future = data(DEMO_OWNER, put("/equipments/" + equipment + "/calendar"), Map.of("shiftStart", "09:00",
                "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5), "effectiveFrom", "2041-09-09",
                "reason", "Future maintenance rotation"));
            assertEquals(2, future.path("versions").size());
            version = future.path("versions").get(1).path("scheduleId").asText();
        }

        String path() {
            return "/equipments/" + equipment + "/calendar/versions/" + version;
        }
    }

    private Map<String, List<Map<String, Object>>> stored(Calendar calendar) {
        return Map.of("versions", jdbc.queryForList("select * from equipment_schedule where equipment_id=? order by schedule_id", calendar.equipment),
            "shifts", jdbc.queryForList("select * from equipment_calendar where equipment_id=? order by shift_id", calendar.equipment));
    }

    private ProjectMember member(String project, String actor, String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId(UUID.randomUUID().toString());
        member.setProjectId(project);
        member.setUserId(actor);
        member.setProjectRole(role);
        member.setMemberStatus("active");
        member.setInvitedBy(DEMO_OWNER);
        return members.saveAndFlush(member);
    }

    private static MockHttpServletRequestBuilder command(String path, String operation) {
        return "read".equals(operation) ? get(path) : delete(path);
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(actor, request, body).andReturn().getResponse();
        assertEquals(200, response.getStatus(), request.toString());
        return mapper.readTree(response.getContentAsString()).path("data");
    }
}
