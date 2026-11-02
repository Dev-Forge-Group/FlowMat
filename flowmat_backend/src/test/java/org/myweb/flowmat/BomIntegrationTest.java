package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** BOM v1 (docs/domain/inventory-bom-lot-contract.md §5) against real Postgres. */
@AutoConfigureMockMvc
class BomIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private ProductionRunItemRepository productionRunItemRepository;

    @Test
    void requirementsFollowTheDesignExampleAndConvertUnits() throws Exception {
        String product = item("unit_ea");
        String flour = item("unit_kg");
        String salt = item("unit_kg");
        String bomId = approvedBom(product, "100", "ea",
            line(flour, "20", "kg"),
            line(salt, "500", "g"));

        call(get("/boms/" + bomId + "/requirements").param("quantity", "250"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(50))
            .andExpect(jsonPath("$.data.lines[0].itemUnit").value("kg"))
            // 250 / 100 × 500 g = 1250 g = 1.25 kg
            .andExpect(jsonPath("$.data.lines[1].requiredQuantity").value(1250))
            .andExpect(jsonPath("$.data.lines[1].requiredItemQuantity").value(1.25))
            .andExpect(jsonPath("$.data.lines[1].conversionRate").value(0.001));
    }

    @Test
    void aDraftBomCannotBeUsedByProduction() throws Exception {
        String product = item("unit_ea");
        String bomId = draftBom(product, "10", "ea", line(item("unit_kg"), "1", "kg"));

        startRun(product, bomId, "5")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("only use an approved revision")));
    }

    @Test
    void anApprovedRevisionCannotChange() throws Exception {
        String product = item("unit_ea");
        String bomId = approvedBom(product, "10", "ea", line(item("unit_kg"), "1", "kg"));

        call(post("/boms/" + bomId + "/lines"), line(item("unit_kg"), "2", "kg")).andExpect(status().isConflict());
        call(put("/boms/" + bomId), "{\"baseQuantity\":20}").andExpect(status().isConflict());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isConflict());
    }

    @Test
    void aRunKeepsItsSnapshotWhenANewRevisionIsApproved() throws Exception {
        String product = item("unit_ea");
        String material = item("unit_kg");
        String v1 = approvedBom(product, "100", "ea", line(material, "20", "kg"));

        String runId = data(startRun(product, v1, "250")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(v1))
            .andExpect(jsonPath("$.data.bomVersion").value(1))).path("productionRunId").asText();

        // v2 doubles the material and replaces v1, which is retired explicitly: approval retires nothing (M1).
        String v2 = data(call(post("/boms/" + v1 + "/revisions")).andExpect(status().isOk())).path("bomId").asText();
        String v2LineId = data(call(get("/boms/" + v2))).path("lines").get(0).path("bomLineId").asText();
        call(delete("/boms/" + v2 + "/lines/" + v2LineId)).andExpect(status().isOk());
        call(post("/boms/" + v2 + "/lines"), line(material, "40", "kg")).andExpect(status().isOk());
        call(post("/boms/" + v2 + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + v1 + "/retire")).andExpect(status().isOk());
        call(post("/boms/" + v2 + "/approve")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bomVersion").value(2));
        call(get("/boms/" + v1)).andExpect(jsonPath("$.data.bomStatus").value("retired"));

        ProductionRunItem planned = productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(runId).get(0);
        assertThat(planned.getQuantitySource()).isEqualTo("bom");
        assertThat(planned.getPlannedQty()).isEqualByComparingTo("50");
        assertThat(planned.getConversionRate()).isEqualByComparingTo(BigDecimal.ONE);
        // A retired revision can no longer start runs.
        startRun(product, v1, "10").andExpect(status().isBadRequest());
    }

    @Test
    void approvalReportsEveryProblem() throws Exception {
        String product = item("unit_ea");
        String material = item("unit_kg");
        String bomId = draftBom(product, "10", "ea",
            line(product, "1", "ea"),
            line(material, "1", "kg"),
            line(material, "2", "kg"),
            line(item("unit_kg"), "1", "m"),
            "{\"childItemId\":\"" + item("unit_kg") + "\",\"quantity\":1,\"unit\":\"kg\",\"lineType\":\"by_product\",\"optionalYn\":\"Y\"}");

        call(post("/boms/" + bomId + "/submit"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(allOf(
                containsString("is the item this BOM produces"),
                containsString("appears more than once"),
                containsString("Cannot record m (length)"),
                containsString("only a material line can be optional"))));
    }

    @Test
    void multiLevelBomsAreApprovedButLoopsAreNot() throws Exception {
        String product = item("unit_ea");
        String subAssembly = item("unit_ea");
        String subAssemblyBom = approvedBom(subAssembly, "1", "ea", line(item("unit_kg"), "1", "kg"));
        // A material with its own approved BOM is a sub-assembly (docs/domain/multi-level-bom.md).
        approvedBom(product, "1", "ea", line(subAssembly, "2", "ea"));

        // A new revision of the sub-assembly made from the product would contain itself.
        String loop = data(call(post("/boms/" + subAssemblyBom + "/revisions")).andExpect(status().isOk())).path("bomId").asText();
        call(post("/boms/" + loop + "/lines"), line(product, "1", "ea")).andExpect(status().isOk());
        call(post("/boms/" + loop + "/submit"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("a BOM cannot contain itself")));
    }

    @Test
    void rejectingNeedsAReasonAndReturnsToDraft() throws Exception {
        String product = item("unit_ea");
        String bomId = draftBom(product, "1", "ea", line(item("unit_kg"), "1", "kg"));
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());

        call(post("/boms/" + bomId + "/reject")).andExpect(status().isBadRequest());
        call(post("/boms/" + bomId + "/reject"), "{\"note\":\"wrong quantities\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomStatus").value("draft"));
        call(post("/boms/" + bomId + "/lines"), line(item("unit_kg"), "1", "kg")).andExpect(status().isOk());
    }

    @Test
    void aWorkOrderCarriesItsBomIntoTheRuns() throws Exception {
        String product = item("unit_ea");
        String material = item("unit_kg");
        String bomId = draftBom(product, "100", "ea", line(material, "20", "kg"));

        // A BOM for another item is refused; an order without a product takes the BOM's product.
        call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"x\",\"targetItemId\":\""
            + item("unit_ea") + "\",\"bomId\":\"" + bomId + "\"}").andExpect(status().isBadRequest());
        String orderId = data(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Bread\","
            + "\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"targetQuantity\":250,\"bomId\":\"" + bomId + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetItemId").value(product))
            .andExpect(jsonPath("$.data.bomId").value(bomId))).path("workOrderId").asText();

        // The order cannot be approved while its BOM is still a draft.
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("approve it or pick an approved revision")));
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());

        // A run started from the order plans its materials from the order's BOM without naming it.
        String runId = data(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":250,\"workOrderId\":\"" + orderId + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(bomId))).path("productionRunId").asText();
        ProductionRunItem planned = productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(runId).get(0);
        assertThat(planned.getPlannedQty()).isEqualByComparingTo("50");
    }

    // ---- helpers ----

    private String approvedBom(String product, String base, String unit, String... lines) throws Exception {
        String bomId = draftBom(product, base, unit, lines);
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bomStatus").value("approved"));
        return bomId;
    }

    private String draftBom(String product, String base, String unit, String... lines) throws Exception {
        String bomId = data(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Test BOM\",\"baseQuantity\":" + base + ",\"baseUnit\":\"" + unit + "\"}")
            .andExpect(status().isOk())).path("bomId").asText();
        for (String line : lines) {
            call(post("/boms/" + bomId + "/lines"), line).andExpect(status().isOk());
        }
        return bomId;
    }

    private ResultActions startRun(String product, String bomId, String quantity) throws Exception {
        return call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + DEMO_WORKFLOW
            + "\",\"targetItemId\":\"" + product + "\",\"plannedOutputQty\":" + quantity + ",\"bomId\":\"" + bomId + "\"}");
    }

    private static String line(String itemId, String quantity, String unit) {
        return "{\"childItemId\":\"" + itemId + "\",\"quantity\":" + quantity + ",\"unit\":\"" + unit + "\"}";
    }

    private String item(String unitId) {
        String id = "itm-bom-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}
