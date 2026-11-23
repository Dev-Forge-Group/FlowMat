package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
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
class WorkInstructionConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(strings = {"text", "add_step", "remove_step", "delete", "release", "revise"})
    @Timeout(45)
    void waitingOperationsMustUseTheReleasedInstruction(String operation) throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbcTemplate.queryForObject("select instruction_id from work_instruction where instruction_id = ? for update",
                    String.class, fixture.instructionId());
                jdbcTemplate.update("update work_instruction set status = 'released', released_by = ?, released_at = current_timestamp "
                    + "where instruction_id = ?", DEMO_OWNER, fixture.instructionId());
                int blockerPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
                Future<Integer> request = pool.submit(() -> operation(operation, fixture));
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, blockerPid);
                return request;
            });

            assertEquals("revise".equals(operation) ? 200 : 409, pending.get(15, TimeUnit.SECONDS));
            JsonNode instructions = data(call(get("/work-instructions").param("projectId", DEMO_PROJECT).param("itemId", fixture.itemId())));
            JsonNode released = instructions.get("revise".equals(operation) ? 1 : 0);
            assertEquals("revise".equals(operation) ? 2 : 1, instructions.size());
            assertEquals(fixture.instructionId(), released.path("instructionId").asText());
            assertEquals("released", released.path("status").asText());
            assertEquals(fixture.title(), released.path("title").asText());
            assertEquals(true, released.path("blocksFinish").asBoolean());
            assertEquals(2, released.path("steps").size());
            assertEquals("First", released.path("steps").get(0).path("text").asText());
            assertEquals("Second", released.path("steps").get(1).path("text").asText());
            if ("revise".equals(operation)) {
                assertEquals("draft", instructions.get(0).path("status").asText());
                assertEquals(2, instructions.get(0).path("revisionNo").asInt());
                assertEquals(2, instructions.get(0).path("steps").size());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int operation(String operation, Fixture fixture) throws Exception {
        String base = "/work-instructions/" + fixture.instructionId();
        ResultActions result = switch (operation) {
            case "text" -> call(put(base), Map.of("title", "Changed after release", "blocksFinish", false));
            case "add_step" -> call(post(base + "/steps"), Map.of("text", "Added after release"));
            case "remove_step" -> call(delete(base + "/steps/" + fixture.firstStepId()));
            case "delete" -> call(delete(base));
            case "release" -> call(post(base + "/release"));
            case "revise" -> call(post(base + "/revise"));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String title = "Frozen " + tag;
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "WI-RACE-" + tag,
            "itemName", "Instruction race " + tag, "unitId", "unit_kg"))).path("itemId").asText();
        String instructionId = data(call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "title", title, "blocksFinish", true))).path("instructionId").asText();
        JsonNode instruction = data(call(post("/work-instructions/" + instructionId + "/steps"), Map.of("text", "First")));
        String firstStepId = instruction.path("steps").get(0).path("stepId").asText();
        data(call(post("/work-instructions/" + instructionId + "/steps"), Map.of("text", "Second")));
        return new Fixture(itemId, instructionId, firstStepId, title);
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

    private record Fixture(String itemId, String instructionId, String firstStepId, String title) {
    }
}
