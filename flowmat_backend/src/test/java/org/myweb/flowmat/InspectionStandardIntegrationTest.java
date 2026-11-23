package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Inspection standards and a run's quality checklist (docs/domain/inspection-standard.md) against real Postgres. */
@AutoConfigureMockMvc
class InspectionStandardIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void standardsAreOnePerItemCheckAndStageAndKeepTheirLimitsInOrder() throws Exception {
        String item = item();
        String moisture = id(standard(item, "Moisture", "production", "10", "12", "%", true), "standardId");

        standard(item, "moisture", "any", null, null, null, false).andExpect(status().isConflict());
        standard(item, "Moisture", "production", null, null, null, false).andExpect(status().isConflict());
        standard(item, "Moisture", "receipt", "9", "13", "%", false).andExpect(status().isOk());
        standard(item, "Weight", "production", "5", "4", "kg", false)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The lower limit is above the upper limit."));
        standard(item, "Weight", "later", null, null, null, false).andExpect(status().isBadRequest());

        call(get("/inspection-standards").param("projectId", DEMO_PROJECT).param("itemId", item))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].stage").value("receipt"))
            .andExpect(jsonPath("$.data[1].required").value(true));

        call(put("/inspection-standards/" + moisture), "{\"inspectionType\":\"Moisture\",\"stage\":\"production\","
            + "\"standardMin\":10,\"standardMax\":11,\"unit\":\"%\",\"required\":true,\"active\":false}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.standardMax").value(11))
            .andExpect(jsonPath("$.data.active").value(false));
        inspect("{\"itemId\":\"" + item + "\",\"standardId\":\"" + moisture + "\",\"inspectionType\":\"Moisture\","
            + "\"measuredValue\":10.5}").andExpect(status().isConflict());

        call(delete("/inspection-standards/" + moisture)).andExpect(status().isOk());
        call(get("/inspection-standards").param("projectId", DEMO_PROJECT).param("itemId", item))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void aRunsChecklistFollowsTheStandardsOfWhatItMakes() throws Exception {
        String raw = item();
        String product = item();
        String rawStock = id(stock(raw, "20"), "inventoryId");
        String productStock = id(stock(product, "0"), "inventoryId");
        String moisture = id(standard(product, "Moisture", "production", "10", "12", "%", true), "standardId");
        id(standard(product, "Visual", "any", null, null, null, true), "standardId");
        id(standard(product, "Colour", "production", null, null, null, false), "standardId");
        id(standard(product, "Supplier paper", "receipt", null, null, null, true), "standardId");
        id(standard(raw, "Moisture", "production", "1", "2", "%", true), "standardId");

        String runId = id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}").andExpect(status().isOk()), "productionRunId");
        call(post("/production-runs/" + runId + "/items"), recording(rawStock, raw, "input", "5")).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/items"), recording(productStock, product, "output", "4")).andExpect(status().isOk());

        // Only what the run makes counts, and receipt checks do not: moisture and visual required, colour optional.
        call(get("/production-runs/" + runId + "/quality-checklist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.required").value(2))
            .andExpect(jsonPath("$.data.requiredMissing").value(2))
            .andExpect(jsonPath("$.data.lines.length()").value(3))
            .andExpect(jsonPath("$.data.lines[2].inspectionType").value("Colour"));

        // Following the standard takes its limits; sending other limits or another check is refused.
        inspect(run(runId, "{\"itemId\":\"" + product + "\",\"standardId\":\"" + moisture + "\",\"inspectionType\":\"Moisture\","
            + "\"measuredValue\":11,\"standardMax\":20}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("The standard sets the limits")));
        inspect(run(runId, "{\"standardId\":\"" + moisture + "\",\"inspectionType\":\"Weight\",\"measuredValue\":11}"))
            .andExpect(status().isBadRequest());
        inspect(run(runId, "{\"itemId\":\"" + raw + "\",\"standardId\":\"" + moisture + "\",\"inspectionType\":\"Moisture\","
            + "\"measuredValue\":11}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("This standard is for a different item."));
        inspect(run(runId, "{\"standardId\":\"" + moisture + "\",\"inspectionType\":\"moisture\",\"measuredValue\":12.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultStatus").value("fail"))
            .andExpect(jsonPath("$.data.standardMin").value(10))
            .andExpect(jsonPath("$.data.unit").value("%"))
            .andExpect(jsonPath("$.data.standardId").value(moisture))
            .andExpect(jsonPath("$.data.itemId").value(product));
        // A check recorded by name, without the standard, counts too; the latest inspection decides.
        inspect(run(runId, "{\"itemId\":\"" + product + "\",\"inspectionType\":\"VISUAL\",\"result\":\"pass\"}"))
            .andExpect(status().isOk());

        call(get("/production-runs/" + runId + "/quality-checklist"))
            .andExpect(jsonPath("$.data.requiredMissing").value(0))
            .andExpect(jsonPath("$.data.requiredPassed").value(1))
            .andExpect(jsonPath("$.data.failed").value(1))
            .andExpect(jsonPath("$.data.lines[0].inspectionType").value("Moisture"))
            .andExpect(jsonPath("$.data.lines[0].status").value("fail"))
            .andExpect(jsonPath("$.data.lines[0].measuredValue").value(12.5));

        inspect(run(runId, "{\"standardId\":\"" + moisture + "\",\"inspectionType\":\"Moisture\",\"measuredValue\":11}"))
            .andExpect(status().isOk());
        call(get("/production-runs/" + runId + "/quality-checklist"))
            .andExpect(jsonPath("$.data.requiredPassed").value(2))
            .andExpect(jsonPath("$.data.failed").value(0));
        callAs("unrelated-user", get("/production-runs/" + runId + "/quality-checklist"), null).andExpect(status().isForbidden());
    }

    private ResultActions standard(String item, String check, String stage, String min, String max, String unit, boolean required)
        throws Exception {
        return call(post("/inspection-standards"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item
            + "\",\"inspectionType\":\"" + check + "\",\"stage\":\"" + stage + "\""
            + (min == null ? "" : ",\"standardMin\":" + min) + (max == null ? "" : ",\"standardMax\":" + max)
            + (unit == null ? "" : ",\"unit\":\"" + unit + "\"") + ",\"required\":" + required + "}");
    }

    private ResultActions inspect(String fields) throws Exception {
        return call(post("/quality-inspections"), "{\"projectId\":\"" + DEMO_PROJECT + "\"," + fields.substring(1));
    }

    private static String run(String runId, String fields) {
        return "{\"productionRunId\":\"" + runId + "\"," + fields.substring(1);
    }

    private static String recording(String inventoryId, String itemId, String direction, String qty) {
        return "{\"inventoryId\":\"" + inventoryId + "\",\"itemId\":\"" + itemId + "\",\"direction\":\"" + direction
            + "\",\"plannedQty\":" + qty + ",\"actualQty\":" + qty + ",\"unit\":\"kg\"}";
    }

    private ResultActions stock(String itemId, String quantity) throws Exception {
        return call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"QS-" + suffix().substring(0, 6) + "\"}").andExpect(status().isOk());
    }

    private String item() {
        String id = "itm-std-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request, null);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private String id(ResultActions result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        return data.path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
