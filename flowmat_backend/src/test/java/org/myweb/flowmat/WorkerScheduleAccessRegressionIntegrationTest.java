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

/** A schedule belongs to one project and worker; its history retains project read/write permissions (ADR-013). */
@AutoConfigureMockMvc
class WorkerScheduleAccessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private ProjectMemberRepository members;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0} cannot {1}")
    @CsvSource({"anonymous,read,401", "anonymous,delete,401", "outsider,read,403", "outsider,delete,403", "viewer,delete,403"})
    void refusedActorsDoNotReadOrDeleteAnotherWorkersSchedule(String role, String operation, int expected) throws Exception {
        Schedule schedule = new Schedule();
        String actor = "anonymous".equals(role) ? null : "wa-" + UUID.randomUUID();
        if ("viewer".equals(role)) member(schedule.projectId, actor, "viewer");
        JsonNode before = view(schedule);

        call(actor, command(schedule.path(), operation), null).andExpect(status().is(expected));

        assertEquals(before, view(schedule), "Refused requests must retain the version, shifts and rota");
    }

    @ParameterizedTest(name = "{1} refuses a schedule of another {0}")
    @CsvSource({"worker,read", "worker,delete", "project,read", "project,delete"})
    void scheduleIdsCannotBeMovedAcrossAuthorizedPaths(String scope, String operation) throws Exception {
        Schedule first = new Schedule();
        Schedule other;
        if ("worker".equals(scope)) {
            String worker = "ww-" + UUID.randomUUID();
            member(first.projectId, worker, "editor");
            other = new Schedule(first.projectId, worker);
        } else {
            other = new Schedule();
        }
        JsonNode firstBefore = view(first);
        JsonNode otherBefore = view(other);
        String wrongPath = "/projects/" + other.projectId + "/workers/" + other.workerId + "/schedules/" + first.scheduleId;

        call(DEMO_OWNER, command(wrongPath, operation), null).andExpect(status().isNotFound());

        assertEquals(firstBefore, view(first));
        assertEquals(otherBefore, view(other));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor"})
    void removingMembershipRevokesBothHistoryAndDeleteAccessWithTheSameToken(String role) throws Exception {
        Schedule project = new Schedule();
        String actor = "wm-" + UUID.randomUUID();
        ProjectMember member = member(project.projectId, actor, role);
        Schedule schedule = new Schedule(project.projectId, actor);
        String token = jwt.generateAccessToken(actor);
        Map<String, List<Map<String, Object>>> before = stored(schedule);
        mvc.perform(get(schedule.path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        member.setMemberStatus("removed");
        members.saveAndFlush(member);

        mvc.perform(get(schedule.path()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(delete(schedule.path()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        assertEquals(before, stored(schedule));
    }

    @Test
    void aViewerReadsAndOnlyTheOwnerOrTheWorkerCanDeleteTheFutureVersion() throws Exception {
        Schedule schedule = new Schedule();
        String viewer = "wv-" + UUID.randomUUID();
        String editor = "we-" + UUID.randomUUID();
        member(schedule.projectId, viewer, "viewer");
        member(schedule.projectId, editor, "editor");
        JsonNode version = data(viewer, get(schedule.path()), null);
        assertEquals(schedule.scheduleId, version.path("scheduleId").asText());
        assertEquals("2041-09-09", version.path("effectiveFrom").asText());
        assertEquals(40, version.path("weeklyHours").asInt());
        assertTrue(version.path("calendarSet").asBoolean());
        JsonNode before = view(schedule);
        call(editor, delete(schedule.path()), null).andExpect(status().isForbidden());
        assertEquals(before, view(schedule), "WC7 does not let an editor change another worker's calendar");
        Schedule own = new Schedule(schedule.projectId, editor);
        JsonNode ownAfter = data(editor, delete(own.path()), null);
        assertEquals(0, ownAfter.path("schedules").size());
        call(DEMO_OWNER, get(own.path()), null).andExpect(status().isNotFound());
        JsonNode after = data(DEMO_OWNER, delete(schedule.path()), null);
        assertEquals(0, after.path("schedules").size());
        call(DEMO_OWNER, get(schedule.path()), null).andExpect(status().isNotFound());
    }

    private final class Schedule {
        final String projectId;
        final String workerId;
        final String scheduleId;

        Schedule() throws Exception {
            this(data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Schedule access " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText(), DEMO_OWNER);
        }

        Schedule(String projectId, String workerId) throws Exception {
            this.projectId = projectId;
            this.workerId = workerId;
            JsonNode saved = data(DEMO_OWNER, put("/projects/" + projectId + "/workers/" + workerId + "/shifts"), Map.of(
                "effectiveFrom", "2041-09-09", "shifts", List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
                    "workDays", List.of(1, 2, 3, 4, 5)))));
            assertEquals(1, saved.path("schedules").size());
            scheduleId = saved.path("schedules").get(0).path("scheduleId").asText();
        }

        String path() {
            return "/projects/" + projectId + "/workers/" + workerId + "/schedules/" + scheduleId;
        }
    }

    private JsonNode view(Schedule schedule) throws Exception {
        return data(DEMO_OWNER, get(schedule.path()), null);
    }

    private Map<String, List<Map<String, Object>>> stored(Schedule schedule) {
        return Map.of(
            "schedule", jdbc.queryForList("select * from worker_schedule where schedule_id = ?", schedule.scheduleId),
            "shifts", jdbc.queryForList("select * from worker_shift where schedule_id = ? order by worker_shift_id", schedule.scheduleId),
            "rota", jdbc.queryForList("select * from worker_rota where schedule_id = ?", schedule.scheduleId));
    }

    private ProjectMember member(String projectId, String userId, String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId(UUID.randomUUID().toString());
        member.setProjectId(projectId);
        member.setUserId(userId);
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
        var result = call(actor, request, body).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getRequest().getRequestURI());
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
