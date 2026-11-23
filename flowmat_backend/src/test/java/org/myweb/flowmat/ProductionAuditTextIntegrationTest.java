package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionAuditTextIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    static String[] invalidText() {
        return new String[] {"\u0001", "Original\u0000", "bad\ud800text", "bad\udc00text"};
    }

    @ParameterizedTest(name = "invalid title case {index}")
    @MethodSource("invalidText")
    void invalidTitlesCannotCreateAWorkOrder(String title) throws Exception {
        int before = orderCount();
        call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "targetQuantity", 1)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("workOrderTitle")));
        assertEquals(before, orderCount());
    }

    @ParameterizedTest(name = "invalid replacement title case {index}")
    @MethodSource("invalidText")
    void rejectedTitlesLeaveTheWorkOrderUnchanged(String title) throws Exception {
        String original = "Audit " + UUID.randomUUID();
        String order = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", original, "targetQuantity", 1))).path("workOrderId").asText();
        call(put("/work-orders/" + order), Map.of("workOrderTitle", title, "targetQuantity", 1))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("workOrderTitle")));
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderTitle").value(original));
    }

    @ParameterizedTest(name = "invalid cancellation reason case {index}")
    @MethodSource("invalidText")
    void rejectedCancellationCannotReverseStockOrCancelTheRecording(String reason) throws Exception {
        Recording recording = inputRecording();
        call(post("/production-runs/" + recording.run() + "/items/" + recording.item() + "/cancel"),
            Map.of("reason", reason)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("reason")));
        call(get("/production-runs/" + recording.run() + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].cancelled").value(false))
            .andExpect(jsonPath("$.data[0].cancelReason").value(nullValue()));
        call(get("/inventories/" + recording.stock())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(8));
        call(get("/inventory-transactions").param("inventoryId", recording.stock())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.transactionType == 'reversal')]").isEmpty());
    }

    @ParameterizedTest(name = "invalid correction reason case {index}")
    @MethodSource("invalidText")
    void rejectedCorrectionRequestsDoNotCreateAnApprovalOrChangeOutput(String reason) throws Exception {
        String run = finishedRun();
        call(post("/production-runs/" + run + "/corrections"), correction(reason))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("reason")));
        call(get("/production-runs/" + run + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        assertOutputUnchanged(run);
    }

    @ParameterizedTest(name = "invalid correction rejection note case {index}")
    @MethodSource("invalidText")
    void rejectedDecisionNotesKeepTheCorrectionWaitingForApproval(String note) throws Exception {
        String run = finishedRun();
        String correction = data(call(post("/production-runs/" + run + "/corrections"),
            correction("Original reason"))).path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + run + "/corrections/" + correction + "/reject"), Map.of("note", note))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("note")));
        call(get("/production-runs/" + run + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("pending_approval"))
            .andExpect(jsonPath("$.data[0].reason").value("Original reason"))
            .andExpect(jsonPath("$.data[0].decisionNote").value(nullValue()));
        assertOutputUnchanged(run);
    }

    @Test
    void validUnicodeReasonsKeepTheirTextAndCompleteTheExistingTransitions() throws Exception {
        Recording recording = inputRecording();
        call(post("/production-runs/" + recording.run() + "/items/" + recording.item() + "/cancel"),
            Map.of("reason", " \t중복 😀\u0001기록 ")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cancelReason").value("중복 😀\u0001기록"))
            .andExpect(jsonPath("$.data.cancelled").value(true));
        call(get("/inventories/" + recording.stock())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(10));
        String run = finishedRun();
        String correction = data(call(post("/production-runs/" + run + "/corrections"),
            correction(" \t수량 정정 😀 ")).andExpect(jsonPath("$.data.reason").value("수량 정정 😀")))
            .path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + run + "/corrections/" + correction + "/reject"),
            Map.of("note", " \t확인 😀\u0001기록 ")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("rejected"))
            .andExpect(jsonPath("$.data.decisionNote").value("확인 😀\u0001기록"));
        assertOutputUnchanged(run);
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("select count(*) from work_order where project_id = ?", Integer.class, DEMO_PROJECT);
    }

    private String run() throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 10))).path("productionRunId").asText();
    }

    private String finishedRun() throws Exception {
        String run = run();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 10))
            .andExpect(status().isOk());
        return run;
    }

    private Recording inputRecording() throws Exception {
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "AUDIT-" + UUID.randomUUID(), "itemName", "Audit material",
            "itemType", "material", "unitId", "unit_ea"))).path("itemId").asText();
        String stock = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "quantity", 10))).path("inventoryId").asText();
        String run = run();
        String recording = data(call(post("/production-runs/" + run + "/items"), Map.of("itemId", item,
            "inventoryId", stock, "direction", "input", "plannedQty", 2, "actualQty", 2, "unit", "ea")))
            .path("productionRunItemId").asText();
        return new Recording(run, recording, stock);
    }

    private Map<String, ?> correction(String reason) {
        return Map.of("reason", reason, "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", 1)));
    }

    private void assertOutputUnchanged(String run) throws Exception {
        call(get("/production-runs/" + run)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runStatus").value("finished"))
            .andExpect(jsonPath("$.data.actualOutputQty").value(10));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }

    private record Recording(String run, String item, String stock) {}
}
