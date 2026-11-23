package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/** Disposal cost and a run's waste disposal cost (docs/domain/bom-by-products.md WD1-WD6). Fixtures stay in the disposable DB. */
@AutoConfigureMockMvc
class ItemDisposalCostIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theCostIsVersionedHistoryKeptAndZeroIsKnown() throws Exception {
        String item = item("1");
        call(get(path(item)), null).andExpect(jsonPath("$.data.disposalCost").isEmpty()).andExpect(jsonPath("$.data.version").value(0));
        call(put(path(item)), "{\"disposalCost\":2.5,\"expectedVersion\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1));
        // The author's exact retry after a lost reply is answered; a stale save of something else is refused.
        call(put(path(item)), "{\"disposalCost\":2.5,\"expectedVersion\":0}").andExpect(jsonPath("$.data.version").value(1));
        call(put(path(item)), "{\"disposalCost\":3,\"expectedVersion\":0}").andExpect(status().isConflict());
        call(put(path(item)), "{\"disposalCost\":0,\"expectedVersion\":1}").andExpect(jsonPath("$.data.disposalCost").value(0));
        call(put(path(item)), "{\"disposalCost\":0,\"expectedVersion\":2}").andExpect(jsonPath("$.data.version").value(3));
        call(put(path(item)), "{\"disposalCost\":null,\"expectedVersion\":3}").andExpect(jsonPath("$.data.disposalCost").isEmpty());
        // Three changes (none -> 2.5 -> 0 -> none); saving 0 again was no change.
        assertEquals(3, jdbc.queryForObject("select count(*) from item_disposal_cost_history where item_id = ?", Integer.class, item));
        assertEquals(0, new BigDecimal("1").compareTo(jdbc.queryForObject("select unit_cost from item where item_id = ?", BigDecimal.class, item)));
        call(get(path(item) + "/history"), null)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].previousDisposalCost").value(0))
            .andExpect(jsonPath("$.data[0].disposalCost").isEmpty())
            .andExpect(jsonPath("$.data[2].previousDisposalCost").isEmpty())
            .andExpect(jsonPath("$.data[2].disposalCost").value(2.5));

        for (String body : new String[]{"{\"disposalCost\":-1,\"expectedVersion\":4}", "{\"disposalCost\":1.23456,\"expectedVersion\":4}",
            "{\"expectedVersion\":4}", "{\"disposalCost\":\"a\",\"expectedVersion\":4}", "{\"disposalCost\":1}", "{"})
            call(put(path(item)), body).andExpect(status().isBadRequest());
        call(get(path("no-such-item")), null).andExpect(status().isNotFound());
        String outsider = user("outsider");
        String viewer = user("viewer");
        callAs(outsider, get(path(item)), null).andExpect(status().isForbidden());
        callAs(viewer, get(path(item)), null).andExpect(status().isOk());
        callAs(viewer, get(path(item) + "/history"), null).andExpect(status().isOk());
        callAs(outsider, get(path(item) + "/history"), null).andExpect(status().isForbidden());
        callAs(viewer, put(path(item)), "{\"disposalCost\":1,\"expectedVersion\":4}").andExpect(status().isForbidden());
    }

    @Test
    void aRunsWasteIsPricedApartAtItsBasis() throws Exception {
        String product = item("1");
        String peel = item("9");
        String sludge = item("9");
        String unknown = item("9");
        String byProduct = item("5");
        String bom = bom(product);
        line(bom, peel, "waste");
        line(bom, sludge, "waste");
        line(bom, unknown, "waste");
        line(bom, byProduct, "by_product");
        // Peel cost 3 from December 2029 and 4 from February 2030; sludge is free to get rid of; unknown has no cost.
        disposal(peel, "4");
        history(peel, "2029-12-01T00:00:00Z", null, "3");
        history(peel, "2030-02-01T00:00:00Z", "3", "4");
        disposal(sludge, "0");
        history(sludge, "2029-12-01T00:00:00Z", null, "0");
        String run = run(bom);
        record(run, peel, "5", "kg", false);
        record(run, peel, "2", "kg", true);
        record(run, sludge, "1000", "g", false);
        record(run, unknown, "1", "kg", false);
        record(run, byProduct, "1", "kg", false);

        // Running: today's costs. Unit cost (9) is never used for waste.
        call(get("/production-runs/" + run + "/waste-disposal-cost"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.costBasis").value("CURRENT"))
            .andExpect(jsonPath("$.data.lines.length()").value(3))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + peel + "')].cost").value(20.0))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + sludge + "')].quantity").value(1.0))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + sludge + "')].cost").value(0.0))
            .andExpect(jsonPath("$.data.disposalCost").value(20))
            .andExpect(jsonPath("$.data.costComplete").value(false));

        // Finished on 1 January 2030: peel's cost then was 3.
        jdbc.update("update production_run set run_status='finished', actual_end_at='2030-01-01T00:00:00Z' where production_run_id=?", run);
        call(get("/production-runs/" + run + "/waste-disposal-cost"), null)
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + peel + "')].cost").value(15.0))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + peel + "')].costBasis").value("HISTORICAL"))
            .andExpect(jsonPath("$.data.disposalCost").value(15));
        // Material cost and by-product value stay as they were.
        call(get("/production-runs/" + run + "/cost"), null).andExpect(jsonPath("$.data.materialCost").value(0));
        call(get("/production-runs/" + run + "/by-product-value"), null).andExpect(jsonPath("$.data.byProductValue").value(5));
        // A run without a BOM has no waste to price; outsiders see nothing.
        call(get("/production-runs/" + run(null) + "/waste-disposal-cost"), null).andExpect(jsonPath("$.data.lines.length()").value(0));
        callAs(user("outsider"), get("/production-runs/" + run + "/waste-disposal-cost"), null).andExpect(status().isForbidden());
    }

    @Test
    void aWorkOrdersWasteIsEstimatedForWhatIsStillToMake() throws Exception {
        String product = item("1");
        String peel = item("9");
        String unknown = item("9");
        String byProduct = item("5");
        String bom = bom(product);
        line(bom, item("2"), "material");
        line(bom, peel, "waste");
        line(bom, unknown, "waste");
        line(bom, byProduct, "by_product");
        disposal(peel, "4");
        String order = data(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + DEMO_WORKFLOW
            + "\",\"workOrderTitle\":\"Waste estimate\",\"targetItemId\":\"" + product + "\",\"targetQuantity\":3,\"bomId\":\""
            + bom + "\"}").andExpect(status().isOk())).path("workOrderId").asText();

        // 3 kg still to make: 3 kg of peel at 4, and waste with no disposal cost left out of the known subtotal (WD8).
        call(get("/work-orders/" + order + "/waste-disposal-estimate"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(3.0))
            .andExpect(jsonPath("$.data.disposalCost").value(12.0))
            .andExpect(jsonPath("$.data.costComplete").value(false))
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + peel + "')].cost").value(hasItem(12.0)))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + unknown + "')].cost").value(hasItem(nullValue())));

        // A finished physical run of the order made 1 kg, so 2 kg remain.
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into production_run(production_run_id,project_id,workflow_id,run_number,run_type,run_status,bom_id,planned_output_qty,actual_output_qty,work_order_id) values(?,?,?,?,'actual','finished',?,1,1,?)",
            id, DEMO_PROJECT, DEMO_WORKFLOW, "DISP-" + id.substring(0, 8), bom, order);
        call(get("/work-orders/" + order + "/waste-disposal-estimate"), null)
            .andExpect(jsonPath("$.data.quantity").value(2.0))
            .andExpect(jsonPath("$.data.disposalCost").value(8.0));

        callAs(user("outsider"), get("/work-orders/" + order + "/waste-disposal-estimate"), null).andExpect(status().isForbidden());
    }

    private com.fasterxml.jackson.databind.JsonNode data(ResultActions result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String path(String item) {
        return "/items/" + item + "/disposal-cost";
    }

    private String item(String price) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,unit_cost) values(?,?,?,?,?,?)",
            id, DEMO_PROJECT, "DSP-" + id.substring(0, 8), "Disposal test", "unit_kg", new BigDecimal(price));
        return id;
    }

    private String bom(String product) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,base_quantity,base_unit,bom_status) values(?,?,?,'Disposal test',1,'kg','approved')",
            id, DEMO_PROJECT, product);
        return id;
    }

    private void line(String bom, String item, String type) {
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type) values(?,?,?,1,'kg',?)",
            UUID.randomUUID().toString(), bom, item, type);
    }

    private void disposal(String item, String cost) {
        jdbc.update("insert into item_disposal_cost(item_id,disposal_cost,version,updated_by,updated_at) values(?,?,1,?,now())",
            item, new BigDecimal(cost), DEMO_OWNER);
    }

    private void history(String item, String at, String before, String after) {
        jdbc.update("insert into item_disposal_cost_history(item_disposal_cost_history_id,project_id,item_id,previous_disposal_cost,disposal_cost,changed_by,changed_at) values(?,?,?,?,?,?,cast(? as timestamptz))",
            UUID.randomUUID().toString(), DEMO_PROJECT, item, before == null ? null : new BigDecimal(before),
            after == null ? null : new BigDecimal(after), DEMO_OWNER, at);
    }

    private String run(String bom) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into production_run(production_run_id,project_id,workflow_id,run_number,run_type,run_status,bom_id,planned_output_qty,actual_output_qty) values(?,?,?,?,'simulation','running',?,2,2)",
            id, DEMO_PROJECT, DEMO_WORKFLOW, "DISP-" + id.substring(0, 8), bom);
        return id;
    }

    private void record(String run, String item, String quantity, String unit, boolean cancelled) {
        jdbc.update("insert into production_run_item(production_run_item_id,production_run_id,item_id,direction,planned_qty,actual_qty,unit,cancelled_yn) values(?,?,?,'output',0,?,?,?)",
            UUID.randomUUID().toString(), run, item, new BigDecimal(quantity), unit, cancelled ? "Y" : "N");
    }

    private String user(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            id, id + "@test.local", DEMO_OWNER);
        if (!"outsider".equals(role)) {
            jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        }
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
