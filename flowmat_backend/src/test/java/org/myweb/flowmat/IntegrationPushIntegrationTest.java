package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Pushing a project's stock to another system (docs/architecture/adr/ADR-038-integration-jobs.md decisions 2 and 5):
 * read the target, send only what differs, read it back. The target is a fake system behind the connector gateway that
 * keeps quantities by item code. Each test has its own project, so the items considered are only the test's own.
 */
@AutoConfigureMockMvc
class IntegrationPushIntegrationTest extends IntegrationTestSupport {

    private enum Answer { OK, LOST, REJECTED, NOT_SENT }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @MockitoBean private ConnectorTargetPolicy targets;
    @MockitoBean private ConnectorHttpGateway gateway;

    /** What the target system holds, by item code. */
    private final Map<String, BigDecimal> target = new LinkedHashMap<>();
    private final List<JsonNode> sent = new ArrayList<>();
    private Answer answer;
    private boolean applies;
    private boolean readable;

    @BeforeEach
    void fakeTarget() {
        target.clear();
        sent.clear();
        answer = Answer.OK;
        applies = true;
        readable = true;
        reset(targets, gateway);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            ConnectorProfileOperation operation = call.getArgument(0);
            if ("levels".equals(operation.operationId())) {
                if (!readable) return new ConnectorHttpOutcome("HTTP_REJECTED", 500, null, "HTTP_STATUS_REJECTED", true);
                ObjectNode levels = json.createObjectNode();
                ArrayNode stock = levels.putArray("stock");
                target.forEach((code, quantity) -> stock.addObject().put("sku", code).put("onHand", quantity));
                return new ConnectorHttpOutcome("SUCCEEDED", 200, levels, null, true);
            }
            JsonNode body = call.getArgument(1);
            sent.add(body);
            if (answer == Answer.NOT_SENT) return new ConnectorHttpOutcome("TRANSPORT_FAILED", null, null, "CONNECT_FAILED", false);
            if (answer == Answer.REJECTED) return new ConnectorHttpOutcome("HTTP_REJECTED", 422, null, "HTTP_STATUS_REJECTED", true);
            if (applies) {
                JsonNode items = body.has("items") ? body.get("items") : body.path("data");
                items.forEach(item -> target.put(item.path("itemCode").asText(), item.path("quantity").decimalValue()));
            }
            if (answer == Answer.LOST) return new ConnectorHttpOutcome("OUTCOME_UNKNOWN", null, null, "READ_TIMEOUT", true);
            return new ConnectorHttpOutcome("SUCCEEDED", 200, json.createObjectNode().put("accepted", true), null, true);
        }).when(gateway).execute(any(), any());
    }

    @Test
    void aPushSendsOnlyTheItemsThatDifferAndReadsThemBack() throws Exception {
        String projectId = project();
        String tag = tag();
        String oats = "PUSH-OATS-" + tag;
        String rye = "PUSH-RYE-" + tag;
        String salt = "PUSH-SALT-" + tag;
        item(projectId, oats);
        item(projectId, rye);
        item(projectId, salt);
        receive(projectId, oats, "5");
        receive(projectId, rye, "3");
        target.put(oats, new BigDecimal("5.000"));
        target.put(rye, BigDecimal.ONE);
        target.put("THEIRS-" + tag, new BigDecimal("9"));
        String jobId = job(projectId, null);

        // A preview reads the target and says what would be sent; nothing is sent.
        JsonNode preview = data(call(post(jobs(projectId) + "/" + jobId + "/preview"), null));
        assertThat(preview.path("status").asText()).isEqualTo("succeeded");
        assertThat(preview.path("message").asText()).isEqualTo("1 items would be sent. 1 of the items the target lists are not in FlowMat.");
        assertThat(results(preview)).containsExactly(oats + ":same", rye + ":send", "THEIRS-" + tag + ":not_in_flowmat");
        assertThat(preview.path("items").get(1).path("message").asText()).isEqualTo("The target has 1; FlowMat has 3.");
        assertThat(sent).isEmpty();

        // The run sends FlowMat's document with the item that differs, then reads it back.
        JsonNode run = data(call(post(jobs(projectId) + "/" + jobId + "/runs"), null));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("items")).hasSize(1);
        assertThat(sent.get(0).path("items").get(0).path("itemCode").asText()).isEqualTo(rye);
        assertThat(sent.get(0).path("items").get(0).path("quantity").toString()).isEqualTo("3");
        assertThat(sent.get(0).path("items").get(0).path("unit").asText()).isEqualTo("kg");
        assertThat(run.path("status").asText()).isEqualTo("succeeded");
        assertThat(results(run)).containsExactly(oats + ":same", rye + ":matched", "THEIRS-" + tag + ":not_in_flowmat");
        assertThat(run.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(run.path("rowsLeftOut").asInt()).isEqualTo(1);

        // Sending again sends nothing: quantities are sent as they are, so nothing adds twice.
        JsonNode again = data(call(post(jobs(projectId) + "/" + jobId + "/runs"), null));
        assertThat(again.path("message").asText()).startsWith("Every item already matches the target.");
        assertThat(sent).hasSize(1);
        assertThat(target.get(rye)).isEqualByComparingTo("3");

        // A push may go on a schedule.
        call(put(jobs(projectId) + "/" + jobId + "/schedule"), "{\"cron\":\"0 6 * * *\",\"enabled\":true,\"version\":1}")
            .andExpect(status().isOk());
    }

    @Test
    void aRequestMappingShapesTheBodyAndATargetThatDoesNotTakeTheUpdateIsSaid() throws Exception {
        String projectId = project();
        String oats = "PUSH-OATS-" + tag();
        item(projectId, oats);
        receive(projectId, oats, "4");
        String jobId = job(projectId, "{\"object\":{\"data\":{\"from\":\"/items\"}}}");
        applies = false;

        JsonNode run = data(call(post(jobs(projectId) + "/" + jobId + "/runs"), null));
        assertThat(sent.get(0).has("items")).isFalse();
        assertThat(sent.get(0).path("data").get(0).path("itemCode").asText()).isEqualTo(oats);
        assertThat(run.path("status").asText()).isEqualTo("partial");
        assertThat(run.path("message").asText()).isEqualTo("1 of 1 items sent do not match after the update.");
        assertThat(run.path("items").get(0).path("result").asText()).isEqualTo("not_listed");
    }

    @Test
    void whatTheTargetHoldsAfterwardsDecidesARunWhoseAnswerWasLostOrRefused() throws Exception {
        String projectId = project();
        String oats = "PUSH-OATS-" + tag();
        item(projectId, oats);
        receive(projectId, oats, "4");
        String jobId = job(projectId, null);
        String runs = jobs(projectId) + "/" + jobId + "/runs";

        answer = Answer.NOT_SENT;
        JsonNode notSent = data(call(post(runs), null));
        assertThat(notSent.path("status").asText()).isEqualTo("failed");
        assertThat(notSent.path("message").asText()).isEqualTo("The update could not be sent (CONNECT_FAILED).");
        assertThat(notSent.path("items").get(0).path("result").asText()).isEqualTo("not_sent");

        answer = Answer.REJECTED;
        applies = false;
        JsonNode refused = data(call(post(runs), null));
        assertThat(refused.path("status").asText()).isEqualTo("failed");
        assertThat(refused.path("message").asText()).isEqualTo("The target refused the update with HTTP 422.");

        answer = Answer.LOST;
        JsonNode lostAndNotTaken = data(call(post(runs), null));
        assertThat(lostAndNotTaken.path("status").asText()).isEqualTo("outcome_unknown");
        assertThat(lostAndNotTaken.path("message").asText())
            .isEqualTo("The update's answer was lost; 1 of 1 items sent do not match when read back.");

        applies = true;
        JsonNode lostButTaken = data(call(post(runs), null));
        assertThat(lostButTaken.path("status").asText()).isEqualTo("succeeded");
        assertThat(lostButTaken.path("message").asText()).isEqualTo("The update's answer was lost; reading back shows every item sent matches.");

        // A target that cannot be read first is not sent to.
        target.clear();
        readable = false;
        int before = sent.size();
        JsonNode unread = data(call(post(runs), null));
        assertThat(unread.path("status").asText()).isEqualTo("failed");
        assertThat(unread.path("message").asText()).isEqualTo("The target answered HTTP 500 when read.");
        assertThat(sent).hasSize(before);
    }

    @Test
    void aPushIsSavedOnlyWithASendingOperationAndAReadingOne() throws Exception {
        String projectId = project();
        String version = profile(projectId);
        String good = """
            {"name":"Push","kind":"push_stock","profileVersionId":"%s","operationId":"update","verifyOperationId":"levels",
             "mapping":{"rowsAt":"/stock","row":{"object":{"itemCode":{"from":"/sku"},"quantity":{"from":"/onHand"}}}}}
            """.formatted(version);
        call(post(jobs(projectId)), good.replace("\"operationId\":\"update\"", "\"operationId\":\"levels\"")).andExpect(status().isBadRequest());
        call(post(jobs(projectId)), good.replace(",\"verifyOperationId\":\"levels\"", "")).andExpect(status().isBadRequest());
        call(post(jobs(projectId)), good.replace("\"verifyOperationId\":\"levels\"", "\"verifyOperationId\":\"update\"")).andExpect(status().isBadRequest());
        call(post(jobs(projectId)), good.replace(",\"quantity\":{\"from\":\"/onHand\"}", "")).andExpect(status().isBadRequest());
        call(post(jobs(projectId)), good.replace("}}}}", "}},\"request\":{\"oops\":1}}}")).andExpect(status().isBadRequest());
        // A pull takes no request mapping.
        call(post(jobs(projectId)), good.replace("push_stock", "pull_stock").replace("\"operationId\":\"update\"", "\"operationId\":\"levels\"")
            .replace("}}}}", "}},\"request\":{\"literal\":1}}}")).andExpect(status().isBadRequest());
        JsonNode job = data(call(post(jobs(projectId)), good));
        assertThat(job.path("verifyOperationId").asText()).isEqualTo("levels");
    }

    private String job(String projectId, String request) throws Exception {
        String mapping = "{\"rowsAt\":\"/stock\",\"row\":{\"object\":{\"itemCode\":{\"from\":\"/sku\"},\"quantity\":{\"from\":\"/onHand\"}}}"
            + (request == null ? "" : ",\"request\":" + request) + "}";
        return data(call(post(jobs(projectId)), """
            {"name":"Push stock","kind":"push_stock","profileVersionId":"%s","operationId":"update","verifyOperationId":"levels",
             "mapping":%s}
            """.formatted(profile(projectId), mapping))).path("jobId").asText();
    }

    private String profile(String projectId) throws Exception {
        return data(call(post("/projects/" + projectId + "/http-profiles"), """
            {"name":"Warehouse %s","baseOrigin":"https://warehouse.example.com","enabled":true,
             "operations":[{"operationId":"levels","method":"GET","relativePath":"/stock","successStatuses":[200]},
                           {"operationId":"update","method":"POST","relativePath":"/stock","successStatuses":[200]}]}
            """.formatted(tag()))).path("profileVersionId").asText();
    }

    private String project() throws Exception {
        return data(call(post("/projects"), json.writeValueAsString(Map.of("projectName", "Push " + tag(), "ownerId", DEMO_OWNER))))
            .path("projectId").asText();
    }

    private void item(String projectId, String code) throws Exception {
        data(call(post("/items"), json.writeValueAsString(Map.of("projectId", projectId, "itemCode", code, "itemName", code,
            "unitId", "unit_kg"))));
    }

    private void receive(String projectId, String code, String quantity) throws Exception {
        data(call(post("/inventories/import"), json.writeValueAsString(Map.of("projectId", projectId, "dryRun", false,
            "rows", List.of(Map.of("itemCode", code, "quantity", quantity))))));
    }

    private static List<String> results(JsonNode run) {
        List<String> found = new ArrayList<>();
        run.path("items").forEach(item -> found.add(item.path("key").asText() + ":" + item.path("result").asText()));
        return found;
    }

    private static String jobs(String projectId) {
        return "/projects/" + projectId + "/integration-jobs";
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
