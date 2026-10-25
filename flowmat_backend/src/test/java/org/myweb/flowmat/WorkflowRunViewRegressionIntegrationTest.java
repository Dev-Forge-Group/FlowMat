package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessIoRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunView;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class WorkflowRunViewRegressionIntegrationTest extends IntegrationTestSupport {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}},\"required\":[\"amount\"]}";

    @Autowired private WorkflowRunQuery workflows;
    @Autowired private EntityManager entities;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest
    @EnumSource(Kind.class)
    void uninitializedReferencesKeepTheViewAndItsRuleFactsWithoutOrmFields(Kind kind) {
        Fixture fixture = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            entities.clear();
            Object reference = switch (kind) {
                case WORKFLOW -> entities.getReference(Workflow.class, fixture.workflowId());
                case PROCESS -> entities.getReference(Process.class, fixture.processId());
                case PORT -> entities.getReference(ProcessIo.class, fixture.portId());
            };
            assertFalse(Hibernate.isInitialized(reference), "The caller must already hold an uninitialized proxy.");

            switch (kind) {
                case WORKFLOW -> {
                    WorkflowRunView view = workflows.findWorkflow(fixture.workflowId()).orElseThrow();
                    assertEquals(fixture.workflowId(), view.workflowId());
                    assertEquals(DEMO_PROJECT, view.projectId());
                    assertEquals(fixture.workflowId(), view.ruleFacts().get("workflowId"));
                    assertEquals(DEMO_PROJECT, view.ruleFacts().get("projectId"));
                    assertEquals("Workflow view", view.ruleFacts().get("workflowName"));
                    assertEquals("Before concurrent edit", view.ruleFacts().get("workflowDesc"));
                    assertEquals("active", view.ruleFacts().get("workflowStatus"));
                    assertEquals("Y", view.ruleFacts().get("lockedYn"));
                    assertEquals(DEMO_OWNER, view.ruleFacts().get("createdBy"));
                    assertNoOrmFields(view);
                    assertReadOnly(view.ruleFacts());
                }
                case PROCESS -> {
                    ProcessRunView view = workflows.findProcess(fixture.processId()).orElseThrow();
                    assertEquals(fixture.processId(), view.processId());
                    assertEquals(DEMO_PROJECT, view.projectId());
                    assertEquals(fixture.workflowId(), view.workflowId());
                    assertEquals("Mixer view", view.processName());
                    assertEquals(fixture.processId(), view.ruleFacts().get("processId"));
                    assertEquals(fixture.workflowId(), view.ruleFacts().get("workflowId"));
                    assertEquals(DEMO_PROJECT, view.ruleFacts().get("projectId"));
                    assertEquals("mixing", view.ruleFacts().get("processType"));
                    assertEquals("Process description", view.ruleFacts().get("processDesc"));
                    assertEquals(91, view.ruleFacts().get("timeoutSeconds"));
                    assertEquals(2, view.ruleFacts().get("retryLimit"));
                    assertEquals("exponential", view.ruleFacts().get("retryBackoff"));
                    assertEquals(123.25, view.ruleFacts().get("posX"));
                    assertNoOrmFields(view);
                    assertReadOnly(view.ruleFacts());
                }
                case PORT -> {
                    ProcessIoRunView view = workflows.findProcessIo(fixture.portId()).orElseThrow();
                    assertEquals(fixture.portId(), view.processIoId());
                    assertEquals(fixture.processId(), view.processId());
                    assertEquals(fixture.itemId(), view.itemId());
                    assertEquals("input", view.direction());
                    assertEquals(fixture.portId(), view.ruleFacts().get("processIoId"));
                    assertEquals(fixture.processId(), view.ruleFacts().get("processId"));
                    assertEquals(fixture.itemId(), view.ruleFacts().get("itemId"));
                    assertEquals("source", view.ruleFacts().get("role"));
                    assertEquals("material", view.ruleFacts().get("resourceType"));
                    assertDecimal("123456.7891", view.ruleFacts().get("quantity"));
                    assertEquals("kg", view.ruleFacts().get("unit"));
                    assertEquals("quantity >= 0", view.ruleFacts().get("validationRule"));
                    assertEquals("Y", view.ruleFacts().get("requiredYn"));
                    assertEquals("N", view.ruleFacts().get("allowShortageYn"));
                    assertNoOrmFields(view);
                    assertReadOnly(view.ruleFacts());
                }
            }
        });
    }

    @Test
    void aProcessSnapshotPreservesPublishedFieldsWithoutChangingTheSource() throws Exception {
        ObjectNode source = (ObjectNode) json.readTree("""
            {"processId":"published-process","workflowId":"published-workflow","projectId":"published-project",
             "processName":"Published mixer","processType":"mixing","nodeType":"process","processStatus":"active",
             "posX":123.25,"posY":-9.5,"width":180.5,"height":88.25,"version":7,"versionNonce":42,
             "processDesc":"Keep the published description","timeoutSeconds":91,"retryLimit":2,
             "retryDelaySeconds":3,"retryBackoff":"exponential","maxRetryDelaySeconds":12,
             "concurrencyLimit":4,"executionPolicyVersion":8,"deletedYn":"N"}
            """);
        JsonNode original = source.deepCopy();

        ProcessRunView view = workflows.snapshotProcess(source);

        assertEquals("published-process", view.processId());
        assertEquals("published-project", view.projectId());
        assertEquals("published-workflow", view.workflowId());
        assertEquals("Published mixer", view.processName());
        assertEquals("published-project", view.ruleFacts().get("projectId"));
        assertEquals("Published mixer", view.ruleFacts().get("processName"));
        assertEquals("Keep the published description", view.ruleFacts().get("processDesc"));
        assertEquals(123.25, view.ruleFacts().get("posX"));
        assertEquals(-9.5, view.ruleFacts().get("posY"));
        assertEquals(180.5, view.ruleFacts().get("width"));
        assertEquals(88.25, view.ruleFacts().get("height"));
        assertEquals(7, view.ruleFacts().get("version"));
        assertEquals(42, view.ruleFacts().get("versionNonce"));
        assertEquals(91, view.ruleFacts().get("timeoutSeconds"));
        assertEquals(2, view.ruleFacts().get("retryLimit"));
        assertEquals(3, view.ruleFacts().get("retryDelaySeconds"));
        assertEquals("exponential", view.ruleFacts().get("retryBackoff"));
        assertEquals(12, view.ruleFacts().get("maxRetryDelaySeconds"));
        assertEquals(4, view.ruleFacts().get("concurrencyLimit"));
        assertEquals(8, ((Number) view.ruleFacts().get("executionPolicyVersion")).intValue());
        assertEquals(original, source, "Reading a published revision must not edit its snapshot.");
        assertReadOnly(view.ruleFacts());
        assertNoOrmFields(view);
    }

    @ParameterizedTest
    @ValueSource(strings = {"object", "text", "null", "absent"})
    void aPortSnapshotAcceptsEachStoredSchemaRepresentationAndKeepsOtherFields(String representation) throws Exception {
        ObjectNode source = (ObjectNode) json.readTree("""
            {"processIoId":"published-port","processId":"published-process","itemId":null,"ioName":"Request data",
             "direction":"output","ioType":"data","role":"result","resourceType":"data","quantity":1.2345,
             "unit":"ea","formula":"amount * 2","validationRule":"amount >= 0","colorScheme":"blue",
             "requiredYn":"Y","allowShortageYn":"N","deletedYn":"N"}
            """);
        switch (representation) {
            case "object" -> source.set("schemaJson", json.readTree(SCHEMA));
            case "text" -> source.put("schemaJson", SCHEMA);
            case "null" -> source.putNull("schemaJson");
            case "absent" -> assertFalse(source.has("schemaJson"));
            default -> throw new IllegalArgumentException(representation);
        }
        JsonNode original = source.deepCopy();

        ProcessIoRunView view = workflows.snapshotProcessIo(source);

        assertEquals("published-port", view.processIoId());
        assertEquals("published-process", view.processId());
        assertNull(view.itemId());
        assertEquals("output", view.direction());
        assertEquals("Request data", view.ruleFacts().get("ioName"));
        assertEquals("data", view.ruleFacts().get("ioType"));
        assertEquals("result", view.ruleFacts().get("role"));
        assertEquals("data", view.ruleFacts().get("resourceType"));
        assertDecimal("1.2345", view.ruleFacts().get("quantity"));
        assertEquals("ea", view.ruleFacts().get("unit"));
        assertEquals("amount * 2", view.ruleFacts().get("formula"));
        assertEquals("amount >= 0", view.ruleFacts().get("validationRule"));
        assertEquals("blue", view.ruleFacts().get("colorScheme"));
        assertEquals("Y", view.ruleFacts().get("requiredYn"));
        assertEquals("N", view.ruleFacts().get("allowShortageYn"));
        assertEquals("null".equals(representation) || "absent".equals(representation) ? null : SCHEMA,
            view.ruleFacts().get("schemaJson"));
        assertEquals(original, source, "Schema normalization must not rewrite the published snapshot.");
        if ("absent".equals(representation)) {
            assertFalse(source.has("schemaJson"), "Reading an old snapshot must leave its absent schema field absent.");
        }
        assertReadOnly(view.ruleFacts());
        assertNoOrmFields(view);
    }

    @Test
    void lockingRequiresTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> workflows.lockWorkflow("missing-workflow"));
    }

    @Test
    void aLockedViewRefreshesAnEarlierReadAfterAnotherTransactionCommits() {
        Fixture fixture = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals("Workflow view", workflows.findWorkflow(fixture.workflowId()).orElseThrow()
                .ruleFacts().get("workflowName"));
            otherTransaction(() -> jdbc.update("update workflow set workflow_name='Committed elsewhere', "
                + "workflow_desc='New description', locked_yn='N' where workflow_id=?", fixture.workflowId()));

            WorkflowRunView locked = workflows.lockWorkflow(fixture.workflowId());

            assertEquals(fixture.workflowId(), locked.workflowId());
            assertEquals(DEMO_PROJECT, locked.projectId());
            assertEquals("Committed elsewhere", locked.ruleFacts().get("workflowName"));
            assertEquals("New description", locked.ruleFacts().get("workflowDesc"));
            assertEquals("N", locked.ruleFacts().get("lockedYn"));
        });
    }

    @Test
    void lockingAMissingOrSoftDeletedWorkflowReturnsNotFoundEvenAfterAnEarlierRead() {
        Fixture fixture = fixture();
        BusinessException deleted = assertThrows(BusinessException.class,
            () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertNotNull(workflows.findWorkflow(fixture.workflowId()).orElseThrow());
            otherTransaction(() -> jdbc.update("update workflow set deleted_yn='Y' where workflow_id=?", fixture.workflowId()));
            workflows.lockWorkflow(fixture.workflowId());
        }));
        assertEquals(ErrorCode.NOT_FOUND, deleted.getErrorCode());
        BusinessException missing = assertThrows(BusinessException.class,
            () -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                workflows.lockWorkflow("missing-" + UUID.randomUUID())));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    @Test
    @Timeout(45)
    void aSecondWorkflowLockWaitsUntilTheFirstCallersTransactionCommits() throws Exception {
        Fixture fixture = fixture();
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<WorkflowRunView> waiting = new TransactionTemplate(transactions).execute(tx -> {
                workflows.lockWorkflow(fixture.workflowId());
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                Future<WorkflowRunView> contender = pool.submit(() -> new TransactionTemplate(transactions)
                    .execute(other -> workflows.lockWorkflow(fixture.workflowId())));
                DatabaseContention.awaitWaitingOrDone(jdbc, contender, blocker);
                assertFalse(contender.isDone(), "The second transaction must reach and wait for the held row lock.");
                return contender;
            });

            assertNotNull(waiting);
            WorkflowRunView acquired = waiting.get(10, TimeUnit.SECONDS);
            assertNotNull(acquired);
            assertEquals(fixture.workflowId(), acquired.workflowId());
            assertEquals("Workflow view", acquired.ruleFacts().get("workflowName"));
        } finally {
            pool.shutdownNow();
        }
    }

    private Fixture fixture() {
        String workflowId = UUID.randomUUID().toString();
        String processId = UUID.randomUUID().toString();
        String portId = UUID.randomUUID().toString();
        String itemId = UUID.randomUUID().toString();
        jdbc.update("insert into workflow(workflow_id,project_id,workflow_name,workflow_desc,workflow_type,workflow_status,"
            + "locked_yn,created_by,updated_by) values(?,?,'Workflow view','Before concurrent edit','main','active','Y',?,?)",
            workflowId, DEMO_PROJECT, DEMO_OWNER, DEMO_OWNER);
        jdbc.update("insert into process(process_id,project_id,workflow_id,process_name,process_type,node_type,process_desc,"
            + "pos_x,pos_y,width,height,timeout_seconds,retry_limit,retry_delay_seconds,retry_backoff,max_retry_delay_seconds,"
            + "concurrency_limit,execution_policy_version) values(?,?,?,'Mixer view','mixing','process','Process description',"
            + "123.25,-9.5,180.5,88.25,91,2,3,'exponential',12,4,8)", processId, DEMO_PROJECT, workflowId);
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id) values(?,?,?,'Workflow view item','unit_kg')",
            itemId, DEMO_PROJECT, "WRV-" + itemId.substring(0, 8));
        jdbc.update("insert into process_io(process_io_id,process_id,item_id,io_name,direction,io_type,role,resource_type,"
            + "quantity,unit,schema_json,validation_rule,required_yn,allow_shortage_yn) "
            + "values(?,?,?,'Input view','input','material','source','material',123456.7891,'kg',cast(? as jsonb),"
            + "'quantity >= 0','Y','N')", portId, processId, itemId, SCHEMA);
        return new Fixture(workflowId, processId, portId, itemId);
    }

    private void otherTransaction(Runnable command) {
        var other = new TransactionTemplate(transactions);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        other.executeWithoutResult(tx -> command.run());
    }

    private void assertNoOrmFields(Object view) {
        JsonNode tree = json.valueToTree(view);
        for (String field : new String[] {"hibernateLazyInitializer", "handler", "fieldHandler", "hibernateInterceptor"}) {
            assertNull(tree.findValue(field), "A public view must not contain " + field);
        }
    }

    private static void assertReadOnly(Map<String, Object> facts) {
        assertThrows(UnsupportedOperationException.class, () -> facts.put("workflowName", "Changed by caller"));
    }

    private static void assertDecimal(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }

    private enum Kind { WORKFLOW, PROCESS, PORT }

    private record Fixture(String workflowId, String processId, String portId, String itemId) {
    }
}
