package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProcessOrder;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** LB34: current live definitions supply process order, and cyclic definitions still yield a usable plan. */
@AutoConfigureMockMvc
class WorkflowProcessOrderRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private WorkflowProcessOrder processOrder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    private String projectId;
    private long nextProcess;

    @BeforeEach
    void separateProject() {
        projectId = id();
        jdbc.update("insert into project(project_id,project_name,owner_id) values(?,'Process order regression',?)",
            projectId, DEMO_OWNER);
    }

    @Test
    void exclusiveJoinAndSplitAreSkippedAndTraversalStopsAtTheNearestProcesses() {
        String workflow = workflow();
        String root = process(workflow, "Root", "process");
        String mix = process(workflow, "Mix", "process");
        String exclusive = process(workflow, "Choose", "Exclusive");
        String split = process(workflow, "Divide", "SPLIT");
        String left = process(workflow, "Left", "process");
        String right = process(workflow, "Right", "process");
        String join = process(workflow, "Wait", "Join");
        String pack = process(workflow, "Pack", "process");
        String ship = process(workflow, "Ship", "process");
        connect(workflow, root, mix);
        connect(workflow, mix, exclusive);
        connect(workflow, exclusive, split);
        connect(workflow, split, left);
        connect(workflow, split, right);
        connect(workflow, left, join);
        connect(workflow, right, join);
        connect(workflow, split, join);
        connect(workflow, exclusive, join);
        connect(workflow, join, pack);
        connect(workflow, pack, ship);

        assertEquals(Map.of(
            root, Set.of(),
            mix, Set.of(root),
            left, Set.of(mix),
            right, Set.of(mix),
            pack, Set.of(left, right, mix),
            ship, Set.of(pack)), processOrder.processPredecessors(workflow));
    }

    @Test
    void softDeletedConnectionsBreakThePathAtEveryGatewayHopAndRestorationIsReadImmediately() {
        String workflow = workflow();
        String source = process(workflow, "Source", "process");
        String exclusive = process(workflow, "Choose", "exclusive");
        String join = process(workflow, "Wait", "join");
        String other = process(workflow, "Other", "process");
        String target = process(workflow, "Target", "process");
        String intoGateway = connect(workflow, source, exclusive);
        String betweenGateways = connect(workflow, exclusive, join);
        String outOfGateway = connect(workflow, join, target);
        connect(workflow, other, target);

        assertEquals(Set.of(source, other), processOrder.processPredecessors(workflow).get(target));
        for (String connection : new String[] {intoGateway, betweenGateways, outOfGateway}) {
            jdbc.update("update process_connection set deleted_yn='Y' where connection_id=?", connection);
            assertEquals(Set.of(other), processOrder.processPredecessors(workflow).get(target),
                "A deleted connection at any point must break a gateway path");
            jdbc.update("update process_connection set deleted_yn='N' where connection_id=?", connection);
            assertEquals(Set.of(source, other), processOrder.processPredecessors(workflow).get(target),
                "The query must use the current connection definition");
        }
    }

    @Test
    void deletedProcessesAndGatewaysAreNeitherReturnedNorTraversedAsShortcuts() {
        String workflow = workflow();
        String source = process(workflow, "Source", "process");
        String deletedProcess = process(workflow, "Removed step", "process");
        String deletedGateway = process(workflow, "Removed gateway", "split");
        String live = process(workflow, "Live", "process");
        String target = process(workflow, "Target", "process");
        connect(workflow, source, deletedProcess);
        connect(workflow, deletedProcess, target);
        connect(workflow, source, deletedGateway);
        connect(workflow, deletedGateway, target);
        connect(workflow, live, target);
        assertEquals(Set.of(deletedProcess, source, live), processOrder.processPredecessors(workflow).get(target));

        jdbc.update("update process set deleted_yn='Y' where process_id in (?,?)", deletedProcess, deletedGateway);

        assertEquals(Map.of(source, Set.of(), live, Set.of(), target, Set.of(live)),
            processOrder.processPredecessors(workflow));
    }

    @Test
    void gatewayCyclesAndSelfLoopsTerminateWithoutMakingAProcessItsOwnPredecessor() {
        String workflow = workflow();
        String source = process(workflow, "Source", "process");
        String exclusive = process(workflow, "Choose", "exclusive");
        String join = process(workflow, "Wait", "join");
        String split = process(workflow, "Divide", "split");
        String target = process(workflow, "Target", "process");
        connect(workflow, source, source);
        connect(workflow, source, exclusive);
        connect(workflow, exclusive, join);
        connect(workflow, join, exclusive);
        connect(workflow, join, target);
        connect(workflow, target, target);
        connect(workflow, target, split);
        connect(workflow, split, target);

        Map<String, Set<String>> result = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> processOrder.processPredecessors(workflow));

        assertEquals(Map.of(source, Set.of(), target, Set.of(source)), result);
    }

    @Test
    void predecessorQueriesStayWithinTheirWorkflowAndEmptyDefinitionsHaveNoOrder() {
        String firstWorkflow = workflow();
        String first = process(firstWorkflow, "First", "process");
        String second = process(firstWorkflow, "Second", "process");
        connect(firstWorkflow, first, second);
        String otherWorkflow = workflow();
        String otherFirst = process(otherWorkflow, "Other first", "process");
        String gateway = process(otherWorkflow, "Other gateway", "join");
        String otherSecond = process(otherWorkflow, "Other second", "process");
        connect(otherWorkflow, otherFirst, gateway);
        connect(otherWorkflow, gateway, otherSecond);

        assertEquals(Map.of(first, Set.of(), second, Set.of(first)), processOrder.processPredecessors(firstWorkflow));
        assertEquals(Map.of(otherFirst, Set.of(), otherSecond, Set.of(otherFirst)),
            processOrder.processPredecessors(otherWorkflow));
        assertTrue(processOrder.processPredecessors(workflow()).isEmpty());
        assertTrue(processOrder.processPredecessors(id()).isEmpty());
    }

    @Test
    void aCycleThroughGatewaysDoesNotDeadlockASplitPlanAndAllWorkIsScheduled() {
        String workflow = workflow();
        String first = process(workflow, "First", "process");
        String second = process(workflow, "Second", "process");
        String exclusive = process(workflow, "Choose", "exclusive");
        String join = process(workflow, "Wait", "join");
        connect(workflow, first, exclusive);
        connect(workflow, exclusive, second);
        connect(workflow, second, join);
        connect(workflow, join, first);
        String order = id();
        jdbc.update("insert into work_order(work_order_id,project_id,workflow_id,work_order_number,work_order_title,target_quantity) "
            + "values(?,?,?,?,'Cyclic split plan',1)", order, projectId, workflow, order);
        String firstWorker = worker(order);
        String secondWorker = worker(order);
        splitPlan(order, first, "First", firstWorker, 60, 0);
        splitPlan(order, second, "Second", secondWorker, 120, 1);
        String start = "2041-05-06T09:00:00+09:00";

        JsonNode result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            String body = mockMvc.perform(get("/work-orders/" + order + "/plan-suggestion").param("from", start)
                    .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data");
        });

        assertEquals(180, result.path("laborMinutes").asInt());
        assertEquals(3.0, result.path("laborHours").asDouble());
        assertEquals(start, result.path("plannedStartAt").asText());
        assertEquals("2041-05-06T12:00:00+09:00", result.path("plannedEndAt").asText());
        JsonNode schedule = result.path("schedule");
        assertEquals(2, schedule.size());
        assertEquals(Set.of(first, second), Set.of(schedule.get(0).path("nodeId").asText(), schedule.get(1).path("nodeId").asText()));
        assertEquals(start, schedule.get(0).path("from").asText());
        assertEquals(schedule.get(0).path("to").asText(), schedule.get(1).path("from").asText());
        assertEquals(result.path("plannedEndAt").asText(), schedule.get(1).path("to").asText());
        for (JsonNode turn : schedule) {
            boolean isFirst = first.equals(turn.path("nodeId").asText());
            assertEquals(isFirst ? firstWorker : secondWorker, turn.path("userId").asText());
            assertEquals(isFirst ? 60L : 120L, Duration.between(OffsetDateTime.parse(turn.path("from").asText()),
                OffsetDateTime.parse(turn.path("to").asText())).toMinutes());
        }
    }

    private String workflow() {
        String workflow = id();
        jdbc.update("insert into workflow(workflow_id,project_id,workflow_name) values(?,?,'Process order graph')", workflow, projectId);
        return workflow;
    }

    private String process(String workflow, String name, String nodeType) {
        String process = id();
        jdbc.update("insert into process(process_id,project_id,workflow_id,process_name,process_type,node_type,created_at) "
            + "values(?,?,?,?,'process',?,?)", process, projectId, workflow, name, nodeType,
            Timestamp.from(Instant.parse("2040-01-01T00:00:00Z").plusSeconds(nextProcess++)));
        return process;
    }

    private String connect(String workflow, String from, String to) {
        String connection = id();
        jdbc.update("insert into process_connection(connection_id,project_id,workflow_id,from_process_id,to_process_id) "
            + "values(?,?,?,?,?)", connection, projectId, workflow, from, to);
        return connection;
    }

    private String worker(String order) {
        String worker = id();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", worker, worker + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'editor')",
            id(), projectId, worker);
        jdbc.update("insert into work_order_crew(crew_id,work_order_id,user_id,added_by,added_at,share_percent) values(?,?,?,?,now(),100)",
            id(), order, worker, DEMO_OWNER);
        String schedule = id();
        jdbc.update("insert into worker_schedule(schedule_id,project_id,user_id,effective_from,updated_by,updated_at) "
            + "values(?,?,?,null,?,now())", schedule, projectId, worker, DEMO_OWNER);
        jdbc.update("insert into worker_shift(worker_shift_id,project_id,user_id,shift_start,shift_end,work_days,updated_by,updated_at,rota_week,schedule_id) "
            + "values(?,?,?,'09:00','17:00','1,2,3,4,5',?,now(),1,?)", id(), projectId, worker, DEMO_OWNER, schedule);
        return worker;
    }

    private void splitPlan(String order, String node, String name, String worker, int minutes, int sortOrder) {
        String plan = id();
        jdbc.update("insert into work_order_step_plan(plan_id,work_order_id,node_id,node_name,sort_order,planned_minutes,updated_by,updated_at) "
            + "values(?,?,?,?,?,?,?,now())", plan, order, node, name, sortOrder, minutes, DEMO_OWNER);
        jdbc.update("insert into work_order_step_plan_person(plan_person_id,plan_id,user_id,sort_order,planned_minutes) values(?,?,?,0,?)",
            id(), plan, worker, minutes);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
