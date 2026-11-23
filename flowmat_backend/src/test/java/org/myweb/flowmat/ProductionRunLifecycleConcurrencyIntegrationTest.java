package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
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
class ProductionRunLifecycleConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProductionRunRepository runRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"record", "cancel", "finish", "check", "uncheck", "fefo"})
    @Timeout(45)
    void anOperationWaitingBehindFinishMustUseTheCommittedRunState(String operation) throws Exception {
        Fixture fixture = fixture(operation);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                var run = runRepository.findForUpdate(fixture.runId()).orElseThrow();
                run.setRunStatus("finished");
                run.setActualOutputQty(new BigDecimal("2"));
                run.setFinishedBy(DEMO_OWNER);
                runRepository.flush();
                int blockerPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
                Future<Integer> request = pool.submit(() -> operation(operation, fixture));
                // Observe real database contention, or an early (incorrect) success. No timing-only race assertion.
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, blockerPid);
                return request;
            });

            assertEquals("check".equals(operation) || "uncheck".equals(operation) ? 409 : 400,
                pending.get(15, TimeUnit.SECONDS));
            call(get("/production-runs/" + fixture.runId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runStatus").value("finished"))
                .andExpect(jsonPath("$.data.actualOutputQty").value(2.0));
            call(get("/inventories/" + fixture.stockId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(fixture.stockQuantity()));
            if (fixture.requiredStepId() != null) {
                call(get("/production-runs/" + fixture.runId() + "/instruction"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.checks.length()").value(1))
                    .andExpect(jsonPath("$.data.complete").value(true));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int operation(String operation, Fixture fixture) throws Exception {
        ResultActions result = switch (operation) {
            case "record" -> call(post("/production-runs/" + fixture.runId() + "/items"), recording(fixture.itemId(), fixture.stockId()));
            case "cancel" -> call(post("/production-runs/" + fixture.runId() + "/items/" + fixture.recordingId() + "/cancel"),
                Map.of("reason", "Concurrent cancellation"));
            case "finish" -> call(post("/production-runs/" + fixture.runId() + "/finish"), Map.of("actualOutputQty", 99));
            case "check" -> call(post("/production-runs/" + fixture.runId() + "/instruction/steps/" + fixture.optionalStepId() + "/check"),
                Map.of());
            case "uncheck" -> call(delete("/production-runs/" + fixture.runId() + "/instruction/steps/" + fixture.requiredStepId() + "/check"));
            case "fefo" -> call(post("/production-runs/" + fixture.runId() + "/inputs/fefo"),
                Map.of("itemId", fixture.itemId(), "quantity", 2, "unit", "kg"));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private Fixture fixture(String operation) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "LIFE-" + tag, "itemName", "Lifecycle " + tag, "unitId", "unit_kg",
            "lotManageYn", "fefo".equals(operation) ? "Y" : "N"))).path("itemId").asText();
        Map<String, Object> stock = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "quantity", 10, "location", "LIFE-" + tag));
        if ("fefo".equals(operation)) {
            stock.put("lotId", data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT,
                "itemId", itemId, "lotNo", "LIFE-" + tag))).path("lotId").asText());
        }
        String stockId = data(call(post("/inventories"), stock)).path("inventoryId").asText();
        String requiredStepId = null;
        String optionalStepId = null;
        if ("check".equals(operation) || "uncheck".equals(operation)) {
            String instructionId = data(call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT,
                "itemId", itemId, "title", "Lifecycle " + tag, "blocksFinish", true))).path("instructionId").asText();
            data(call(post("/work-instructions/" + instructionId + "/steps"), Map.of("text", "Required", "required", true)));
            JsonNode instruction = data(call(post("/work-instructions/" + instructionId + "/steps"),
                Map.of("text", "Optional", "required", false)));
            requiredStepId = instruction.path("steps").get(0).path("stepId").asText();
            optionalStepId = instruction.path("steps").get(1).path("stepId").asText();
            data(call(post("/work-instructions/" + instructionId + "/release")));
        }
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Lifecycle " + tag))).path("workflowId").asText();
        JsonNode run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "targetItemId", itemId, "plannedOutputQty", 2)));
        String runId = run.path("productionRunId").asText();
        String recordingId = null;
        if ("cancel".equals(operation)) {
            recordingId = data(call(post("/production-runs/" + runId + "/items"), recording(itemId, stockId)))
                .path("productionRunItemId").asText();
        }
        if (requiredStepId != null) {
            data(call(post("/production-runs/" + runId + "/instruction/steps/" + requiredStepId + "/check"), Map.of()));
        }
        return new Fixture(runId, itemId, stockId, "cancel".equals(operation) ? 8.0 : 10.0,
            recordingId, requiredStepId, optionalStepId);
    }

    private Map<String, ?> recording(String itemId, String stockId) {
        return Map.of("itemId", itemId, "inventoryId", stockId, "direction", "input",
            "plannedQty", 2, "actualQty", 2, "unit", "kg");
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private record Fixture(String runId, String itemId, String stockId, double stockQuantity,
        String recordingId, String requiredStepId, String optionalStepId) {
    }
}
