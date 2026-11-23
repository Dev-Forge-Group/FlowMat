package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.integration.application.IntegrationRunService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Scheduled integration runs (docs/architecture/adr/ADR-038-integration-jobs.md decisions 6–8): the schedule's checks,
 * taking a due run once however many servers poll, running as the person who switched it on, and switching off after
 * three failed runs in a row. The poller is off in tests; each test takes the due runs itself.
 */
@AutoConfigureMockMvc
class IntegrationScheduleIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IntegrationRunService runService;
    @Autowired private ProjectCalendarQuery calendar;
    @MockitoBean private ConnectorTargetPolicy targets;
    @MockitoBean private ConnectorHttpGateway gateway;

    private static final AtomicInteger PAST_MINUTES = new AtomicInteger();

    private final AtomicReference<String> answer = new AtomicReference<>();
    /** Who the services saw as the current user when the source was read. */
    private final AtomicReference<String> readAs = new AtomicReference<>();

    @BeforeEach
    void source() {
        reset(targets, gateway);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            var current = SecurityContextHolder.getContext().getAuthentication();
            readAs.set(current == null ? null : current.getName());
            String body = answer.get();
            return body == null
                ? new ConnectorHttpOutcome("HTTP_REJECTED", 503, null, "HTTP_STATUS_REJECTED", true)
                : new ConnectorHttpOutcome("SUCCEEDED", 200, json.readTree(body), null, true);
        }).when(gateway).execute(any(), any());
    }

    @Test
    void aScheduleIsCheckedThenRunsWhenDueAsThePersonWhoSwitchedItOn() throws Exception {
        String tag = tag();
        String jobId = job("pull_items");
        String path = jobs() + "/" + jobId + "/schedule";
        for (String cron : List.of("*/5 * * * *", "0,10 * * * *", "0 6 * *", "@daily", "0 25 * * *", "0 0 30 2 *")) {
            call(DEMO_OWNER, put(path), schedule(cron, true, 1)).andExpect(status().isBadRequest());
        }
        call(member("viewer"), put(path), schedule("0 6 * * 1-5", true, 1)).andExpect(status().isForbidden());
        call(DEMO_OWNER, put(path), schedule("0 6 * * 1-5", true, 2)).andExpect(status().isConflict());

        JsonNode on = data(call(DEMO_OWNER, put(path), schedule("0  6 * * 1-5", true, 1)));
        assertThat(on.path("scheduleEnabled").asBoolean()).isTrue();
        assertThat(on.path("scheduleCron").asText()).isEqualTo("0 6 * * 1-5");
        assertThat(on.path("runAs").asText()).isEqualTo(DEMO_OWNER);
        ZoneId zone = calendar.zone(DEMO_PROJECT);
        ZonedDateTime expected = CronExpression.parse("0 0 6 * * 1-5").next(ZonedDateTime.now(zone));
        assertThat(OffsetDateTime.parse(on.path("nextRunAt").asText()).toInstant()).isEqualTo(expected.toInstant());

        // Its time comes: the run is taken for that moment, as the owner, and the next time moves on.
        answer.set("{\"items\":[{\"sku\":\"SCH-%s\"}]}".formatted(tag));
        OffsetDateTime due = due(jobId);
        assertThat(runService.runDue()).isPositive();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        JsonNode run = data(call(DEMO_OWNER, get(jobs() + "/" + jobId + "/runs"), null)).get(0);
        assertThat(run.path("trigger").asText()).isEqualTo("schedule");
        assertThat(run.path("status").asText()).isEqualTo("succeeded");
        assertThat(run.path("runBy").asText()).isEqualTo(DEMO_OWNER);
        assertThat(OffsetDateTime.parse(run.path("scheduledFor").asText()).toInstant()).isEqualTo(due.toInstant());
        assertThat(readAs.get()).isEqualTo(DEMO_OWNER);
        assertThat(jdbc.queryForObject("select count(*) from item where item_code = ?", Integer.class, "SCH-" + tag)).isOne();
        assertThat(jdbc.queryForObject("select next_run_at > now() from integration_job where job_id = ?", Boolean.class, jobId)).isTrue();

        // Not due again until then, and a time already run is not run again.
        runService.runDue();
        jdbc.update("update integration_job set next_run_at = ? where job_id = ?", due, jobId);
        runService.runDue();
        assertThat(scheduledRuns(jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_run_at > now() from integration_job where job_id = ?", Boolean.class, jobId)).isTrue();

        JsonNode off = data(call(DEMO_OWNER, put(path), "{\"enabled\":false,\"version\":2}"));
        assertThat(off.path("scheduleEnabled").asBoolean()).isFalse();
        assertThat(off.path("nextRunAt").isNull()).isTrue();
        assertThat(off.path("scheduleCron").asText()).isEqualTo("0 6 * * 1-5");

        // A stock pull receives what it reads, so it does not go on a schedule.
        String stockJob = job("pull_stock");
        call(DEMO_OWNER, put(jobs() + "/" + stockJob + "/schedule"), schedule("0 6 * * *", true, 1)).andExpect(status().isBadRequest());
    }

    @Test
    void twoServersPollingAtOnceTakeADueRunOnce() throws Exception {
        String jobId = job("pull_items");
        data(call(DEMO_OWNER, put(jobs() + "/" + jobId + "/schedule"), schedule("0 6 * * *", true, 1)));
        answer.set("{\"items\":[]}");
        due(jobId);

        ExecutorService servers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> polls = new ArrayList<>();
        for (int server = 0; server < 2; server++) {
            polls.add(servers.submit(() -> {
                start.await();
                return runService.runDue();
            }));
        }
        start.countDown();
        for (Future<Integer> poll : polls) poll.get(60, TimeUnit.SECONDS);
        servers.shutdown();
        assertThat(scheduledRuns(jobId)).isEqualTo(1);
    }

    @Test
    void threeFailedScheduledRunsInARowSwitchTheScheduleOffAndManualRunsDoNotCount() throws Exception {
        String jobId = job("pull_items");
        String path = jobs() + "/" + jobId + "/schedule";
        data(call(DEMO_OWNER, put(path), schedule("0 6 * * *", true, 1)));
        answer.set(null);

        for (int failed = 1; failed <= 2; failed++) {
            due(jobId);
            runService.runDue();
            assertThat(failures(jobId)).isEqualTo(failed);
        }
        assertThat(data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null)).path("status").asText()).isEqualTo("failed");
        assertThat(failures(jobId)).isEqualTo(2);

        due(jobId);
        runService.runDue();
        JsonNode job = data(call(DEMO_OWNER, get(jobs()), null)).findParents("jobId").stream()
            .filter(node -> node.path("jobId").asText().equals(jobId)).findFirst().orElseThrow();
        assertThat(job.path("scheduleEnabled").asBoolean()).isFalse();
        assertThat(job.path("nextRunAt").isNull()).isTrue();
        assertThat(job.path("failuresInRow").asInt()).isEqualTo(3);
        assertThat(job.path("pausedReason").asText())
            .isEqualTo("Switched off after 3 failed runs in a row. The last: The source answered HTTP 503.");

        // Switching it on again starts the count again; a run that works also does.
        JsonNode on = data(call(DEMO_OWNER, put(path), schedule("0 6 * * *", true, job.path("version").asInt())));
        assertThat(on.path("failuresInRow").asInt()).isZero();
        assertThat(on.path("pausedReason").isNull()).isTrue();
        due(jobId);
        runService.runDue();
        answer.set("{\"items\":[]}");
        due(jobId);
        runService.runDue();
        assertThat(failures(jobId)).isZero();
    }

    @Test
    void aScheduleRunsWithNoMoreThanItsPersonCanDo() throws Exception {
        String tag = tag();
        String jobId = job("pull_items");
        data(call(DEMO_OWNER, put(jobs() + "/" + jobId + "/schedule"), schedule("0 6 * * *", true, 1)));
        String viewer = member("viewer");
        jdbc.update("update integration_job set run_as = ? where job_id = ?", viewer, jobId);
        answer.set("{\"items\":[{\"sku\":\"VIEW-%s\"}]}".formatted(tag));
        due(jobId);
        runService.runDue();

        JsonNode run = data(call(DEMO_OWNER, get(jobs() + "/" + jobId + "/runs"), null)).get(0);
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("runBy").asText()).isEqualTo(viewer);
        assertThat(run.path("message").asText())
            .isEqualTo("The schedule runs as " + viewer + ", who can no longer change this project. Switch it on again as someone who can.");
        assertThat(jdbc.queryForObject("select count(*) from item where item_code = ?", Integer.class, "VIEW-" + tag)).isZero();
    }

    @Test
    void aRunLeftRunningByAStoppedServerIsClosedButARunStillGoingIsWaitedFor() throws Exception {
        String jobId = job("pull_items");
        answer.set("{\"items\":[]}");
        String lost = running(jobId, "now() - interval '1 hour'");
        assertThat(data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null)).path("status").asText()).isEqualTo("succeeded");
        assertThat(jdbc.queryForObject("select status from integration_run where run_id = ?", String.class, lost))
            .isEqualTo("outcome_unknown");

        running(jobId, "now()");
        call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null).andExpect(status().isConflict());

        // A scheduled time that comes while the run is going is skipped, not queued.
        JsonNode job = data(call(DEMO_OWNER, get(jobs()), null)).findParents("jobId").stream()
            .filter(node -> node.path("jobId").asText().equals(jobId)).findFirst().orElseThrow();
        data(call(DEMO_OWNER, put(jobs() + "/" + jobId + "/schedule"), schedule("0 6 * * *", true, job.path("version").asInt())));
        due(jobId);
        runService.runDue();
        assertThat(scheduledRuns(jobId)).isZero();
        assertThat(jdbc.queryForObject("select next_run_at > now() from integration_job where job_id = ?", Boolean.class, jobId)).isTrue();
    }

    private String job(String kind) throws Exception {
        String version = data(call(DEMO_OWNER, post("/projects/" + DEMO_PROJECT + "/http-profiles"), """
            {"name":"Schedule %s","baseOrigin":"https://supplier.example.com","enabled":true,
             "operations":[{"operationId":"list","method":"GET","relativePath":"/items","successStatuses":[200]}]}
            """.formatted(tag()))).path("profileVersionId").asText();
        String row = "pull_items".equals(kind)
            ? "{\"itemCode\":{\"from\":\"/sku\"},\"itemName\":{\"literal\":\"Scheduled\"},\"unitCode\":{\"literal\":\"KG\"}}"
            : "{\"itemCode\":{\"from\":\"/sku\"},\"quantity\":{\"from\":\"/qty\"}}";
        return data(call(DEMO_OWNER, post(jobs()), """
            {"name":"Scheduled %s","kind":"%s","profileVersionId":"%s","operationId":"list",
             "mapping":{"rowsAt":"/items","row":{"object":%s}}}
            """.formatted(tag(), kind, version, row))).path("jobId").asText();
    }

    /** Makes the job due at a past minute no earlier call used, as if its time had come; returns that time. */
    private OffsetDateTime due(String jobId) {
        jdbc.update("update integration_job set next_run_at = date_trunc('minute', now()) - make_interval(mins => ?) where job_id = ?",
            PAST_MINUTES.incrementAndGet(), jobId);
        return jdbc.queryForObject("select next_run_at from integration_job where job_id = ?", OffsetDateTime.class, jobId);
    }

    private String running(String jobId, String startedAt) {
        String runId = "run-" + tag();
        jdbc.update("insert into integration_run(run_id, job_id, project_id, run_trigger, status, run_by, started_at) "
            + "values (?, ?, ?, 'manual', 'running', ?, " + startedAt + ")", runId, jobId, DEMO_PROJECT, DEMO_OWNER);
        return runId;
    }

    private int scheduledRuns(String jobId) {
        return jdbc.queryForObject("select count(*) from integration_run where job_id = ? and run_trigger = 'schedule'", Integer.class, jobId);
    }

    private int failures(String jobId) {
        return jdbc.queryForObject("select failures_in_row from integration_job where job_id = ?", Integer.class, jobId);
    }

    private static String schedule(String cron, boolean enabled, int version) {
        return "{\"cron\":\"%s\",\"enabled\":%s,\"version\":%d}".formatted(cron, enabled, version);
    }

    private String member(String role) {
        String user = "schedule-" + role + "-" + tag();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, user, role);
        return user;
    }

    private static String jobs() {
        return "/projects/" + DEMO_PROJECT + "/integration-jobs";
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
