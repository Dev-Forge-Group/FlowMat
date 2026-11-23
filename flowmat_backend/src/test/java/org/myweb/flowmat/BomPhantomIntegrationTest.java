package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Phantom BOM lines (docs/domain/multi-level-bom.md P1-P7) against real Postgres; fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class BomPhantomIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aPhantomLineIsReplacedByTheRevisionOfTheOrdersPlannedDay() throws Exception {
        String flour = item("unit_kg");
        String sugar = item("unit_kg");
        String dough = item("unit_kg");
        String bread = item("unit_ea");
        // Dough is made from flour until 31 January 2030 and from sugar from 1 February 2030.
        String flourDough = bom(dough, 1, null, "2030-01-31");
        line(flourDough, flour, "1");
        String sugarDough = bom(dough, 2, "2030-02-01", null);
        line(sugarDough, sugar, "0.5");
        // 2 kg of dough per loaf, as a phantom.
        String breadBom = draft(bread);
        call(post("/boms/" + breadBom + "/lines"), Map.of("childItemId", dough, "quantity", 2, "unit", "kg", "phantom", true))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].phantom").value(true));
        call(post("/boms/" + breadBom + "/submit"), null).andExpect(status().isOk());
        call(post("/boms/" + breadBom + "/approve"), null).andExpect(status().isOk());

        // Today (before 2030): flour, through dough; no dough line.
        call(get("/boms/" + breadBom + "/requirements").param("quantity", "3"), null)
            .andExpect(jsonPath("$.data.lines.length()").value(1))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(flour))
            .andExpect(jsonPath("$.data.lines[0].requiredItemQuantity").value(6.0))
            .andExpect(jsonPath("$.data.lines[0].viaItemId").value(dough));

        // An order planned for 10 February 2030 freezes sugar into its run (P3).
        Map<String, Object> order = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Phantom order", "targetItemId", bread, "targetQuantity", 3, "bomId", breadBom,
            "plannedStartAt", "2030-02-10T00:00:00Z"));
        String orderId = data(call(post("/work-orders"), order)).path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve"), null).andExpect(status().isOk());
        // Readiness and allocation ask for what the run will freeze: sugar, not today's flour.
        call(get("/work-orders/" + orderId + "/readiness"), null)
            .andExpect(jsonPath("$.data.materials.length()").value(1))
            .andExpect(jsonPath("$.data.materials[0].itemId").value(sugar));
        call(post("/work-orders/" + orderId + "/allocations"), null)
            .andExpect(jsonPath("$.data.plan.length()").value(1))
            .andExpect(jsonPath("$.data.plan[0].itemId").value(sugar))
            .andExpect(jsonPath("$.data.plan[0].needed").value(3.0));
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "plannedOutputQty", 3))).path("productionRunId").asText();
        List<Map<String, Object>> planned = jdbc.queryForList(
            "select item_id, planned_qty, via_item_id from production_run_item where production_run_id = ? and quantity_source = 'bom'", run);
        assertEquals(1, planned.size());
        assertEquals(sugar, planned.get(0).get("item_id"));
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) planned.get(0).get("planned_qty")));
        // The run keeps where the line came from, for its detail screen.
        assertEquals(dough, planned.get(0).get("via_item_id"));
        call(get("/production-runs/" + run + "/items"), null)
            .andExpect(jsonPath("$.data[?(@.itemId == '" + sugar + "')].viaItemId").value(hasItem(dough)));

        // A run without an order uses today's revision.
        String loose = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", bread, "bomId", breadBom, "plannedOutputQty", 1))).path("productionRunId").asText();
        assertEquals(flour, jdbc.queryForObject(
            "select item_id from production_run_item where production_run_id = ? and quantity_source = 'bom'", String.class, loose));

        // The explosion shows the materials, not the phantom.
        call(get("/boms/" + breadBom + "/explosion").param("quantity", "1"), null)
            .andExpect(jsonPath("$.data.materials[*].itemId").value(hasItem(flour)))
            .andExpect(jsonPath("$.data.materials[*].itemId").value(not(hasItem(dough))));
    }

    @Test
    void onlyAMaterialWithItsOwnBomCanBeAPhantom() throws Exception {
        String bread = item("unit_ea");
        String crumbs = item("unit_kg");
        String plain = item("unit_kg");
        String breadBom = draft(bread);
        call(post("/boms/" + breadBom + "/lines"), Map.of("childItemId", crumbs, "quantity", 1, "unit", "kg", "lineType", "by_product",
            "phantom", true))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Only a material line")));
        call(post("/boms/" + breadBom + "/lines"), Map.of("childItemId", plain, "quantity", 1, "unit", "kg", "phantom", true))
            .andExpect(status().isOk());
        call(post("/boms/" + breadBom + "/submit"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("is a phantom but has no approved BOM of its own")));
        // A revision keeps the flag.
        jdbc.update("update bom_header set bom_status = 'retired' where bom_id = ?", breadBom);
        call(post("/boms/" + breadBom + "/revisions"), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].phantom").value(true));
    }

    private String item(String unit) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status) values(?,?,?,?,?,'active')",
            id, DEMO_PROJECT, "PH-" + id.substring(0, 8), "Phantom test", unit);
        return id;
    }

    private String bom(String product, int version, String from, String to) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,bom_version,base_quantity,base_unit,bom_status,approval_status,created_by,effective_from,effective_to) values(?,?,?,'Phantom dough',?,1,'kg','approved','approved',?,cast(? as date),cast(? as date))",
            id, DEMO_PROJECT, product, version, DEMO_OWNER, from, to);
        return id;
    }

    private void line(String bom, String item, String quantity) {
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type) values(?,?,?,?,'kg','material')",
            UUID.randomUUID().toString(), bom, item, new BigDecimal(quantity));
    }

    private String draft(String product) throws Exception {
        return data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", product, "bomName", "Phantom bread",
            "baseQuantity", 1, "baseUnit", "ea"))).path("bomId").asText();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
