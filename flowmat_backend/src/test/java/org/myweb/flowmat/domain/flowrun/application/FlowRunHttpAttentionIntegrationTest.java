package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.SessionStatementCounter;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Project-scoped, read-only review of durable HTTP evidence; no external requests or manufacturing fixtures. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FlowRunHttpAttentionIntegrationTest extends IntegrationTestSupport {
    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-10-20T12:34:56.123456Z");
    private static final List<String> TRANSPORTS = List.of("PENDING", "SUCCEEDED", "HTTP_REJECTED",
        "RESPONSE_REJECTED", "TRANSPORT_FAILED", "OUTCOME_UNKNOWN");
    private static final List<String> APPLICATIONS = List.of("PENDING", "APPLIED", "APPLY_FAILED", "LATE");
    private static final Set<String> ITEM_FIELDS = Set.of("executionId", "flowRunId", "workflowId", "runStatus",
        "stepId", "nodeId", "stepStatus", "attemptId", "operationId", "transportStatus", "applyStatus",
        "httpStatus", "errorCode", "startedAt", "completedAt");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entities;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private ConnectorHttpGateway gateway;

    @AfterEach
    void readingNeverEntersTheHttpGateway() {
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "editor", "viewer"})
    void activeProjectReadersCanReviewEvidence(String role) throws Exception {
        Fixture f = fixture();
        Evidence row = evidence(f, "PENDING", "PENDING", STARTED);
        String reader = "owner".equals(role) ? DEMO_OWNER : member(f.project(), role);

        JsonNode page = page(reader, f.project());

        assertEquals(List.of(row.execution()), ids(page));
        assertTrue(page.has("nextCursor"));
        assertTrue(page.path("nextCursor").isNull());
    }

    @Test
    void anOutsiderCannotInspectAnotherProjectsEvidence() throws Exception {
        Fixture f = fixture();
        evidence(f, "OUTCOME_UNKNOWN", "PENDING", STARTED);
        call(user(), request(f.project())).andExpect(status().isForbidden());
    }

    @Test
    void anonymousReadersAreRejected() throws Exception {
        Fixture f = fixture();
        evidence(f, "PENDING", "PENDING", STARTED);
        mvc.perform(request(f.project())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed", "invited"})
    void everyPageRechecksMembershipEvenWhenTheTokenAndCursorAreUnchanged(String memberStatus) throws Exception {
        Fixture f = fixture();
        evidence(f, "PENDING", "PENDING", STARTED);
        evidence(f, "OUTCOME_UNKNOWN", "PENDING", STARTED.minusSeconds(1));
        String reader = member(f.project(), "viewer");
        String token = jwt.generateAccessToken(reader);
        JsonNode first = data(withToken(token, request(f.project()).param("limit", "1")));
        assertFalse(first.path("nextCursor").isNull());
        jdbc.update("update project_member set member_status=? where project_id=? and user_id=?", memberStatus, f.project(), reader);

        withToken(token, request(f.project()).param("limit", "1").param("cursor", first.path("nextCursor").asText()))
            .andExpect(status().isForbidden());
    }

    @Test
    void deletingTheProjectRevokesTheOwnersExistingCursor() throws Exception {
        Fixture f = fixture();
        evidence(f, "PENDING", "PENDING", STARTED);
        evidence(f, "PENDING", "PENDING", STARTED.minusSeconds(1));
        String token = jwt.generateAccessToken(DEMO_OWNER);
        JsonNode first = data(withToken(token, request(f.project()).param("limit", "1")));
        jdbc.update("update project set deleted_yn='Y',project_status='deleted' where project_id=?", f.project());

        withToken(token, request(f.project()).param("cursor", first.path("nextCursor").asText()))
            .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentProjectsReturnNotFoundRatherThanAnEmptyPage() throws Exception {
        call(DEMO_OWNER, request(id())).andExpect(status().isNotFound());
    }

    @Test
    void pagesAndForeignCursorsNeverIncludeEvidenceFromAnotherProject() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        Evidence one = evidence(first, "PENDING", "PENDING", STARTED);
        Evidence two = evidence(first, "PENDING", "PENDING", STARTED.minusSeconds(2));
        evidence(second, "OUTCOME_UNKNOWN", "PENDING", STARTED.plusSeconds(20));
        Evidence foreignOld = evidence(second, "PENDING", "PENDING", STARTED.minusSeconds(1));
        JsonNode foreign = page(DEMO_OWNER, second.project(), "limit", "1");

        assertEquals(List.of(one.execution(), two.execution()), ids(page(DEMO_OWNER, first.project())));
        JsonNode own = page(DEMO_OWNER, first.project(), "cursor", foreign.path("nextCursor").asText());
        Set<String> ownIds = Set.of(one.execution(), two.execution());
        for (String execution : ids(own)) assertTrue(ownIds.contains(execution));
        assertFalse(ids(own).contains(foreignOld.execution()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "PENDING", "OUTCOME_UNKNOWN", "APPLY_FAILED", "LATE"})
    void statusFiltersApplyToEveryTransportAndApplicationCombination(String state) throws Exception {
        Fixture f = fixture();
        List<String> expected = new ArrayList<>();
        int offset = 0;
        for (String transport : TRANSPORTS) {
            for (String application : APPLICATIONS) {
                Evidence row = evidence(f, transport, application, STARTED.minusSeconds(offset++));
                boolean requiresAttention = Set.of("PENDING", "OUTCOME_UNKNOWN").contains(transport) || !"APPLIED".equals(application);
                boolean selected = "ALL".equals(state)
                    || (Set.of("PENDING", "OUTCOME_UNKNOWN").contains(state) && state.equals(transport))
                    || (Set.of("APPLY_FAILED", "LATE").contains(state) && state.equals(application));
                if (requiresAttention && selected) expected.add(row.execution());
            }
        }

        assertEquals(expected, ids(page(DEMO_OWNER, f.project(), "state", state, "limit", "100")));
        if ("ALL".equals(state)) assertEquals(expected, ids(page(DEMO_OWNER, f.project(), "limit", "100")));
    }

    @Test
    void cancelledAndFailedRunsAndDeletedWorkflowsKeepTheirUnresolvedEvidence() throws Exception {
        Fixture f = fixture();
        Evidence cancelled = evidence(f, "SUCCEEDED", "LATE", STARTED);
        Evidence failed = evidence(f, "OUTCOME_UNKNOWN", "PENDING", STARTED.minusSeconds(1));
        jdbc.update("update flow_run set status='cancelled',ended_at=? where flow_run_id=?", STARTED.plusMinutes(1), cancelled.run());
        jdbc.update("update flow_run_step set status='cancelled',ended_at=? where step_id=?", STARTED.plusMinutes(1), cancelled.step());
        jdbc.update("update flow_run set status='failed',ended_at=? where flow_run_id=?", STARTED.plusMinutes(1), failed.run());
        jdbc.update("update flow_run_step set status='failed',ended_at=? where step_id=?", STARTED.plusMinutes(1), failed.step());
        jdbc.update("update workflow set deleted_yn='Y',workflow_status='deleted' where workflow_id=?", f.workflow());

        JsonNode page = page(DEMO_OWNER, f.project());

        assertEquals(List.of(cancelled.execution(), failed.execution()), ids(page));
        assertEquals("cancelled", page.path("items").get(0).path("runStatus").asText());
        assertEquals("cancelled", page.path("items").get(0).path("stepStatus").asText());
        assertEquals("failed", page.path("items").get(1).path("runStatus").asText());
        assertEquals("failed", page.path("items").get(1).path("stepStatus").asText());
    }

    @Test
    void equalTimestampsUseTheExecutionIdAndNewRowsDoNotShiftTheNextPage() throws Exception {
        Fixture f = fixture();
        List<String> expected = new ArrayList<>();
        String prefix = "att-" + UUID.randomUUID().toString().substring(0, 20);
        for (int index = 1; index <= 5; index++) {
            expected.add(evidence(f, "PENDING", "PENDING", STARTED, prefix + "-" + index).execution());
        }
        expected.sort(Comparator.reverseOrder());
        JsonNode first = page(DEMO_OWNER, f.project(), "limit", "2");
        assertEquals(expected.subList(0, 2), ids(first));
        String cursor = first.path("nextCursor").asText();
        Evidence newlyArrived = evidence(f, "PENDING", "PENDING", STARTED.plusSeconds(1));
        List<String> walked = new ArrayList<>(ids(first));
        int pages = 1;
        while (cursor != null && pages++ < 5) {
            JsonNode next = page(DEMO_OWNER, f.project(), "limit", "2", "cursor", cursor);
            walked.addAll(ids(next));
            cursor = next.path("nextCursor").isNull() ? null : next.path("nextCursor").asText();
        }

        assertNull(cursor, "The finite result set must terminate.");
        assertEquals(expected, walked);
        assertEquals(new HashSet<>(walked).size(), walked.size());
        assertEquals(newlyArrived.execution(), ids(page(DEMO_OWNER, f.project(), "limit", "1")).get(0));
    }

    @Test
    void defaultsReturnTwentyFiveItemsAndTheMaximumLimitIsAccepted() throws Exception {
        Fixture f = fixture();
        for (int index = 0; index < 26; index++) evidence(f, "PENDING", "PENDING", STARTED.minusSeconds(index));
        JsonNode first = page(DEMO_OWNER, f.project());
        assertEquals(25, first.path("items").size());
        assertTrue(first.path("nextCursor").isTextual());
        JsonNode all = page(DEMO_OWNER, f.project(), "limit", "100");
        assertEquals(26, all.path("items").size());
        assertTrue(all.path("nextCursor").isNull());
    }

    @Test
    void aHundredReviewRowsUseTheSameBoundedSqlCountAsOneRow() {
        Fixture f = fixture();
        for (int index = 0; index < 101; index++) evidence(f, "PENDING", "PENDING", STARTED.minusSeconds(index));
        String viewer = member(f.project(), "viewer");
        String token = jwt.generateAccessToken(viewer);

        // Fresh transactions avoid measuring a warm first-level cache, and fixture writes stay outside both samples.
        long one = statementCount(token, f.project(), 1);
        long hundred = statementCount(token, f.project(), 100);

        assertTrue(one >= 2, "The measurement must include project authorization and the review query: " + one);
        assertEquals(one, hundred, "Review metadata must not load a run or step per row: " + one + " versus " + hundred);
        assertTrue(hundred <= 8, "Authorization and the metadata projection must remain bounded bulk reads: " + hundred);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "1.5", "2147483648", "NaN"})
    void malformedLimitsReturnBadRequest(String limit) throws Exception {
        Fixture f = fixture();
        call(DEMO_OWNER, request(f.project()).param("limit", limit)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "pending", "APPLIED", "SUCCEEDED", "", "PENDING,OUTCOME_UNKNOWN"})
    void unknownOrMalformedStatesReturnBadRequest(String state) throws Exception {
        Fixture f = fixture();
        call(DEMO_OWNER, request(f.project()).param("state", state)).andExpect(status().isBadRequest());
    }

    @Test
    void malformedAndOversizedCursorsReturnBadRequest() throws Exception {
        Fixture f = fixture();
        for (String cursor : List.of("not-a-cursor!", encoded("not-an-instant|id"), encoded(STARTED + "|"),
            encoded("|id"), encoded(STARTED + "|id|trailing"), "A".repeat(8192))) {
            call(DEMO_OWNER, request(f.project()).param("cursor", cursor)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theResponseContainsOnlyBoundedReviewMetadataAndLeavesAllPersistedStateUnchanged() throws Exception {
        Fixture f = fixture();
        Evidence row = evidence(f, "SUCCEEDED", "APPLY_FAILED", STARTED);
        Map<String, List<String>> before = snapshot(f.project());

        JsonNode page = page(DEMO_OWNER, f.project());
        JsonNode item = page.path("items").get(0);
        Set<String> fields = new HashSet<>();
        item.fieldNames().forEachRemaining(fields::add);
        assertEquals(ITEM_FIELDS, fields);
        assertEquals(row.execution(), item.path("executionId").asText());
        assertEquals(row.run(), item.path("flowRunId").asText());
        assertEquals(f.workflow(), item.path("workflowId").asText());
        assertEquals("running", item.path("runStatus").asText());
        assertEquals(row.step(), item.path("stepId").asText());
        assertEquals(row.node(), item.path("nodeId").asText());
        assertEquals("running", item.path("stepStatus").asText());
        assertEquals(row.attempt(), item.path("attemptId").asText());
        assertEquals("send", item.path("operationId").asText());
        assertEquals(200, item.path("httpStatus").asInt());
        assertEquals("LOCAL_APPLY_FAILED", item.path("errorCode").asText());
        assertEquals(STARTED.toInstant(), OffsetDateTime.parse(item.path("startedAt").asText()).toInstant());
        assertEquals(STARTED.plusSeconds(1).toInstant(), OffsetDateTime.parse(item.path("completedAt").asText()).toInstant());
        String visible = page.toString();
        for (String hidden : List.of("output", "outputJson", "requestId", "requestedBy", "requestHash", "claimToken",
            "profileVersionId", "credentialAlias", "responseJson", "PRIVATE-PROVIDER-RESPONSE", "PRIVATE-REQUEST-INPUT",
            "PRIVATE-CREDENTIAL", "PRIVATE-CLAIM", row.request(), f.profileVersion())) assertFalse(visible.contains(hidden), hidden);
        assertEquals(before, snapshot(f.project()));
        for (String table : List.of("item", "inventory", "inventory_transaction", "lot_master", "bom_header", "work_order", "production_run")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, f.project()), table);
        }
    }

    private Fixture fixture() {
        String project = id(), workflow = id(), revision = id(), profile = id(), profileVersion = id();
        jdbc.update("insert into project(project_id,project_name,owner_id) values(?,'HTTP review test',?)", project, DEMO_OWNER);
        jdbc.update("insert into workflow(workflow_id,project_id,workflow_name) values(?,?,'HTTP evidence workflow')", workflow, project);
        jdbc.update("insert into workflow_revision(workflow_revision_id,workflow_id,revision_no,status,schema_version,snapshot_json,published_by,published_at) "
            + "values(?,?,1,'published',4,'{}'::jsonb,?,?)", revision, workflow, DEMO_OWNER, STARTED.minusMinutes(1));
        jdbc.update("insert into connector_profile(profile_id,project_id,name,enabled,current_revision,version,created_by,updated_by,created_at,updated_at) "
            + "values(?,?,'HTTP evidence profile',true,1,0,?,?,?,?)", profile, project, DEMO_OWNER, DEMO_OWNER, STARTED, STARTED);
        jdbc.update("insert into connector_profile_version(profile_version_id,profile_id,revision,base_origin,credential_alias,operations_json,created_by,created_at) "
            + "values(?,?,1,'https://never-contact.invalid','PRIVATE-CREDENTIAL',"
            + "'[{\"operationId\":\"send\",\"method\":\"POST\",\"relativePath\":\"/private\",\"successStatuses\":[200]}]'::jsonb,?,?)",
            profileVersion, profile, DEMO_OWNER, STARTED);
        return new Fixture(project, workflow, revision, profileVersion, new AtomicInteger());
    }

    private Evidence evidence(Fixture fixture, String transport, String application, OffsetDateTime started) {
        return evidence(fixture, transport, application, started, id());
    }

    private Evidence evidence(Fixture fixture, String transport, String application, OffsetDateTime started, String execution) {
        String run = id(), step = id(), node = id(), attempt = id(), request = id();
        jdbc.update("insert into flow_run(flow_run_id,project_id,workflow_id,workflow_revision_id,run_type,execution_mode,status,input_payload,started_at,requested_by) "
            + "values(?,?,?,?,'actual','graph','running','{\"private\":\"PRIVATE-REQUEST-INPUT\"}'::jsonb,?,?)",
            run, fixture.project(), fixture.workflow(), fixture.revision(), started, DEMO_OWNER);
        jdbc.update("insert into flow_run_step(step_id,flow_run_id,node_id,status,sequence_no,started_at,input_snapshot) "
            + "values(?,?,?,'running',?,?,'{\"private\":\"PRIVATE-REQUEST-INPUT\"}'::jsonb)",
            step, run, node, fixture.sequence().incrementAndGet(), started);
        jdbc.update("insert into flow_run_step_attempt(attempt_id,step_id,attempt_no,status,started_at) values(?,?,1,'running',?)", attempt, step, started);
        Integer httpStatus = Set.of("PENDING", "OUTCOME_UNKNOWN", "TRANSPORT_FAILED").contains(transport) ? null : 200;
        String errorCode = "APPLY_FAILED".equals(application) ? "LOCAL_APPLY_FAILED" : null;
        OffsetDateTime completed = "PENDING".equals(transport) ? null : started.plusSeconds(1);
        jdbc.update("insert into flow_run_http_execution(execution_id,flow_run_id,step_id,attempt_id,request_id,claim_token,profile_version_id,operation_id,"
            + "request_hash,transport_status,apply_status,http_status,error_code,output_json,requested_by,started_at,completed_at) "
            + "values(?,?,?,?,?,'PRIVATE-CLAIM',?,'send',?,?,?,?,?,'{\"private\":\"PRIVATE-PROVIDER-RESPONSE\"}'::jsonb,?,?,?)",
            execution, run, step, attempt, request, fixture.profileVersion(), "f".repeat(64), transport, application, httpStatus, errorCode,
            DEMO_OWNER, started, completed);
        return new Evidence(execution, run, step, node, attempt, request);
    }

    private String member(String project, String role) {
        String user = user();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)", id(), project, user, role);
        return user;
    }

    private String user() {
        String user = id();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER));
        return user;
    }

    private JsonNode page(String user, String project, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = request(project);
        for (int index = 0; index < parameters.length; index += 2) request.param(parameters[index], parameters[index + 1]);
        return data(call(user, request));
    }

    private JsonNode data(ResultActions response) throws Exception {
        JsonNode envelope = json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(envelope.path("success").asBoolean());
        JsonNode data = envelope.path("data");
        assertTrue(data.path("items").isArray());
        assertTrue(data.has("nextCursor"));
        return data;
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request) throws Exception {
        return withToken(jwt.generateAccessToken(user), request);
    }

    private ResultActions withToken(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private MockHttpServletRequestBuilder request(String project) {
        return get("/projects/" + project + "/http-executions/attention");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(row -> ids.add(row.path("executionId").asText()));
        return ids;
    }

    private Map<String, List<String>> snapshot(String project) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Map<String, String> queries = new HashMap<>();
        queries.put("execution", "select to_jsonb(e)::text from flow_run_http_execution e join flow_run r on r.flow_run_id=e.flow_run_id where r.project_id=? order by e.execution_id");
        queries.put("run", "select to_jsonb(r)::text from flow_run r where r.project_id=? order by r.flow_run_id");
        queries.put("step", "select to_jsonb(s)::text from flow_run_step s join flow_run r on r.flow_run_id=s.flow_run_id where r.project_id=? order by s.step_id");
        queries.put("attempt", "select to_jsonb(a)::text from flow_run_step_attempt a join flow_run_step s on s.step_id=a.step_id join flow_run r on r.flow_run_id=s.flow_run_id where r.project_id=? order by a.attempt_id");
        queries.put("profile", "select to_jsonb(p)::text from connector_profile p where p.project_id=? order by p.profile_id");
        queries.put("profileVersion", "select to_jsonb(v)::text from connector_profile_version v join connector_profile p on p.profile_id=v.profile_id where p.project_id=? order by v.profile_version_id");
        for (Map.Entry<String, String> query : queries.entrySet()) result.put(query.getKey(), jdbc.queryForList(query.getValue(), String.class, project));
        return result;
    }

    private long statementCount(String token, String project, int limit) {
        return new TransactionTemplate(transactions).execute(tx -> {
            try (SessionStatementCounter counter = SessionStatementCounter.start(entities)) {
                JsonNode page = data(withToken(token, request(project).param("limit", Integer.toString(limit))));
                assertEquals(limit, page.path("items").size());
                assertTrue(page.path("nextCursor").isTextual(), "Both samples must measure a bounded lookahead query.");
                return counter.count();
            } catch (Exception failure) {
                throw new AssertionError("The measured review request must succeed.", failure);
            }
        });
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private record Fixture(String project, String workflow, String revision, String profileVersion, AtomicInteger sequence) { }
    private record Evidence(String execution, String run, String step, String node, String attempt, String request) { }
}
