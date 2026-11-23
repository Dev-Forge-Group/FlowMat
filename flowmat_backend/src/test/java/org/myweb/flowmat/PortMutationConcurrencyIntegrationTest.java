package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PortMutationConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource({"create,workflow", "update,workflow", "delete,workflow",
        "create,process", "update,process", "delete,process"})
    @Timeout(45)
    void aWaitingPortMutationRejectsADeletedParent(String operation, String parent) throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                int blocker = holdWorkflow(fixture.workflow());
                if ("workflow".equals(parent)) {
                    jdbc.update("UPDATE workflow SET deleted_yn = 'Y' WHERE workflow_id = ?", fixture.workflow());
                } else {
                    jdbc.update("UPDATE process SET deleted_yn = 'Y' WHERE process_id = ?", fixture.process());
                }
                Future<Integer> request = pool.submit(() -> mutate(operation, fixture));
                DatabaseContention.awaitWaitingOrDone(jdbc, request, blocker);
                return request;
            });
            assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_io WHERE process_id = ?", Integer.class,
                fixture.process())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT io_name FROM process_io WHERE process_io_id = ?", String.class,
                fixture.port())).isEqualTo("Original");
            assertThat(jdbc.queryForObject("SELECT deleted_yn FROM process_io WHERE process_io_id = ?", String.class,
                fixture.port())).isEqualTo("N");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(45)
    void addingARequiredInputWaitsForPublicationAndCannotChangeThePublishedSnapshot() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            PublishedPending published = new TransactionTemplate(transactionManager).execute(transaction -> {
                int blocker = holdWorkflow(fixture.workflow());
                Future<Integer> request = pool.submit(() -> mutate("create", fixture));
                DatabaseContention.awaitWaitingOrDone(jdbc, request, blocker);
                assertThat(request.isDone()).as("Port creation must wait for the publication transaction").isFalse();
                try {
                    String revision = data(call(post("/workflows/" + fixture.workflow() + "/revisions")))
                        .path("workflowRevisionId").asText();
                    return new PublishedPending(revision, request);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertThat(published.request().get(15, TimeUnit.SECONDS)).isEqualTo(200);
            JsonNode revision = data(call(get("/workflows/" + fixture.workflow() + "/revisions/" + published.revision())));
            assertThat(revision.path("snapshot").path("processIos").size()).isEqualTo(1);
            assertThat(revision.path("snapshot").path("processIos").get(0).path("processIoId").asText()).isEqualTo(fixture.port());
            JsonNode validation = data(call(get("/workflows/" + fixture.workflow() + "/validation")));
            assertThat(validation.path("errors").asInt()).isEqualTo(1);
            assertThat(validation.path("issues").get(0).path("code").asText()).isEqualTo("REQUIRED_INPUT_UNCONNECTED");
            assertThat(data(call(get("/process-ios").param("processId", fixture.process()))).size()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    @Timeout(45)
    void aWaitingConnectionMutationRejectsADeletedWorkflow(String operation) throws Exception {
        Fixture fixture = fixture();
        String target = data(call(post("/processes"), Map.of("workflowId", fixture.workflow(), "processName", "Target")))
            .path("processId").asText();
        String connection = data(call(post("/process-connections"), connection(fixture, target, "Original")))
            .path("connectionId").asText();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                int blocker = holdWorkflow(fixture.workflow());
                jdbc.update("UPDATE workflow SET deleted_yn = 'Y' WHERE workflow_id = ?", fixture.workflow());
                Future<Integer> request = pool.submit(() -> mutateConnection(operation, fixture, target, connection));
                DatabaseContention.awaitWaitingOrDone(jdbc, request, blocker);
                return request;
            });
            assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_connection WHERE workflow_id = ?", Integer.class,
                fixture.workflow())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT connection_label FROM process_connection WHERE connection_id = ?", String.class,
                connection)).isEqualTo("Original");
            assertThat(jdbc.queryForObject("SELECT version FROM process_connection WHERE connection_id = ?", Integer.class,
                connection)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT deleted_yn FROM process_connection WHERE connection_id = ?", String.class,
                connection)).isEqualTo("N");
        } finally {
            pool.shutdownNow();
        }
    }

    private int mutateConnection(String operation, Fixture fixture, String target, String connection) throws Exception {
        ResultActions result = switch (operation) {
            case "create" -> call(post("/process-connections"), connection(fixture, target, "New connection"));
            case "update" -> call(put("/process-connections/" + connection), Map.of("connectionLabel", "Changed after deletion"));
            case "delete" -> call(delete("/process-connections/" + connection));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private Map<String, Object> connection(Fixture fixture, String target, String label) {
        return Map.of("workflowId", fixture.workflow(), "fromProcessId", fixture.process(),
            "toProcessId", target, "connectionLabel", label);
    }

    private int holdWorkflow(String workflow) {
        jdbc.queryForObject("SELECT workflow_id FROM workflow WHERE workflow_id = ? FOR UPDATE", String.class, workflow);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private int mutate(String operation, Fixture fixture) throws Exception {
        ResultActions result = switch (operation) {
            case "create" -> call(post("/process-ios"), port(fixture.process(), "input", "New input"));
            case "update" -> call(put("/process-ios/" + fixture.port()), Map.of("ioName", "Changed after deletion"));
            case "delete" -> call(delete("/process-ios/" + fixture.port()));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private Fixture fixture() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Port publication race " + UUID.randomUUID()))).path("workflowId").asText();
        String process = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String port = data(call(post("/process-ios"), port(process, "output", "Original"))).path("processIoId").asText();
        return new Fixture(workflow, process, port);
    }

    private Map<String, Object> port(String process, String direction, String name) {
        return Map.of("processId", process, "itemId", "itm_demo_mix_output", "direction", direction,
            "quantity", 1, "unit", "kg", "ioName", name);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String workflow, String process, String port) {}
    private record PublishedPending(String revision, Future<Integer> request) {}
}
