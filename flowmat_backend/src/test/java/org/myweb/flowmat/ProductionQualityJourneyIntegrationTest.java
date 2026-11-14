package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The production-to-quality journey through real JWT filters and MockMvc, with real Postgres and Redis: an approved BOM's
 * work order is checked, allocated and picked, a run takes the picked LOTs and makes two product LOTs, one passes its
 * check and one fails into a quarantine and a nonconformity, and the genealogy, cost and ledger agree at the end.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ProductionQualityJourneyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anApprovedBomsOrderIsAllocatedPickedProducedInspectedAndTracedWithAReconciledLedger() throws Exception {
        Fixture f = fixture();
        String soon = rawLot(f, "SOON", LocalDate.now().plusDays(10), "6");
        String late = rawLot(f, "LATE", LocalDate.now().plusDays(60), "10");
        String soonStock = stockOf(f, soon);
        String lateStock = stockOf(f, late);
        String bom = approvedBom(f);

        // 20 loaves need 10 kg of flour; 16 kg are on hand, nothing is short.
        JsonNode order = ok(f.editor(), post("/work-orders"), workOrder(f, bom, "20"));
        String orderPath = "/work-orders/" + order.path("workOrderId").asText();
        assertThat(order.path("bomId").asText()).isEqualTo(bom);
        call(f.editor(), post(orderPath + "/approve"), null, 403);
        ok(f.owner(), post(orderPath + "/approve"), null);
        JsonNode readiness = ok(f.viewer(), get(orderPath + "/readiness"), null);
        JsonNode flourNeed = only(readiness.path("materials"), "itemId", f.flour());
        amount("10", flourNeed.path("requiredQuantity"));
        amount("16", flourNeed.path("availableQuantity"));
        amount("0", flourNeed.path("shortageQuantity"));

        // Allocation reserves the soonest-expiring LOT first and moves no stock: its ledger rows are zero-quantity reservations.
        int ledgerBefore = ledgerRows(f);
        JsonNode allocated = ok(f.editor(), post(orderPath + "/allocations"), Map.of());
        amount("10", allocated.path("plan").get(0).path("allocatedNow"));
        amount("0", allocated.path("plan").get(0).path("shortage"));
        List<JsonNode> allocations = elements(allocated.path("allocations"));
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).path("lotId").asText()).isEqualTo(soon);
        amount("6", allocations.get(0).path("quantity"));
        assertThat(allocations.get(1).path("lotId").asText()).isEqualTo(late);
        amount("4", allocations.get(1).path("quantity"));
        assertThat(jdbc.queryForList("select transaction_type, quantity_delta from inventory_transaction where project_id=? "
                + "and reference_type='stock_allocation'", f.project())).hasSize(2).allSatisfy(row -> {
                    assertThat(row.get("transaction_type")).isEqualTo("reserve");
                    assertThat((BigDecimal) row.get("quantity_delta")).isEqualByComparingTo("0");
                });
        assertThat(ledgerRows(f)).isEqualTo(ledgerBefore + 2);
        stock(soonStock, "6", "6");
        stock(lateStock, "10", "4");

        // Picking moves the reserved stock to the line and the reservation goes with it.
        List<String> picked = new ArrayList<>();
        for (JsonNode allocation : allocations) {
            Map<String, Object> pick = Map.of("projectId", f.project(), "workOrderId", order.path("workOrderId").asText(),
                    "allocationId", allocation.path("allocationId").asText(), "fromInventoryId", allocation.path("inventoryId").asText(),
                    "toLocation", "LINE", "quantity", allocation.path("quantity").decimalValue(), "requestId", UUID.randomUUID().toString());
            JsonNode moved = ok(f.editor(), post("/allocated-stock-transfers"), pick);
            Map<String, Object> afterPick = stockAndLedger(f);
            assertThat(ok(f.editor(), post("/allocated-stock-transfers"), pick).path("transferId").asText())
                    .isEqualTo(moved.path("transferId").asText());
            assertThat(stockAndLedger(f)).as("Retrying a pick moves nothing again").isEqualTo(afterPick);
            picked.add(moved.path("in").path("inventoryId").asText());
        }
        String lineSoon = picked.get(0);
        String lineLate = picked.get(1);
        stock(soonStock, "0", "0");
        stock(lateStock, "6", "0");
        stock(lineSoon, "6", "6");
        stock(lineLate, "4", "4");

        // The run of the order takes the picked LOTs and makes two LOTs of bread.
        JsonNode run = ok(f.editor(), post("/production-runs/start"), Map.of("projectId", f.project(), "workflowId", f.workflow(),
                "workOrderId", order.path("workOrderId").asText(), "plannedOutputQty", 20));
        String runId = run.path("productionRunId").asText();
        String runPath = "/production-runs/" + runId;
        assertThat(run.path("bomId").asText()).isEqualTo(bom);
        assertThat(run.path("targetItemId").asText()).isEqualTo(f.bread());
        record(f, runId, f.flour(), lineSoon, "input", "6", "kg");
        record(f, runId, f.flour(), lineLate, "input", "4", "kg");
        String good = productLot(f, "GOOD");
        String bad = productLot(f, "BAD");
        String goodStock = stockOf(f, good);
        String badStock = stockOf(f, bad);
        record(f, runId, f.bread(), goodStock, "output", "12", "ea");
        record(f, runId, f.bread(), badStock, "output", "8", "ea");
        stock(lineSoon, "0", "0");
        stock(lineLate, "0", "0");
        JsonNode used = ok(f.viewer(), get(orderPath + "/allocations"), null);
        for (JsonNode allocation : used.path("allocations")) {
            assertThat(allocation.path("status").asText()).isEqualTo("closed");
            amount("0", allocation.path("remaining"));
        }

        // Each product LOT waits for its checks: one passes and is released, one fails into a quarantine.
        assertThat(ok(f.viewer(), get("/lots/" + good), null).path("lotStatus").asText()).isEqualTo("inspection_pending");
        call(f.editor(), post("/inventory-transactions"), issue(goodStock, "1"), 409);
        ok(f.editor(), post("/quality-inspections"), inspection(f, runId, good, "40"));
        assertThat(ok(f.editor(), post("/lots/" + good + "/release"), null).path("lotStatus").asText()).isEqualTo("available");
        JsonNode failed = ok(f.editor(), post("/quality-inspections"), inspection(f, runId, bad, "55"));
        assertThat(failed.path("resultStatus").asText()).isEqualTo("fail");
        call(f.editor(), post("/lots/" + bad + "/release"), null, 409);
        Map<String, Object> failing = new HashMap<>(inspection(f, runId, bad, "56"));
        failing.put("quarantineLot", true);
        ok(f.editor(), post("/quality-inspections"), failing);
        assertThat(ok(f.viewer(), get("/lots/" + bad), null).path("lotStatus").asText()).isEqualTo("quarantined");
        call(f.editor(), post("/inventory-transactions"), issue(badStock, "1"), 409);

        String defect = ok(f.editor(), post("/defects"), Map.of("projectId", f.project(), "productionRunId", runId, "lotId", bad,
                "itemId", f.bread(), "quantity", 8, "defectType", "Underbaked", "severity", "major",
                "inspectionId", failed.path("inspectionId").asText())).path("defectLogId").asText();
        JsonNode ncr = ok(f.editor(), post("/nonconformities"), Map.of("projectId", f.project(), "title", "Underbaked loaves",
                "itemId", f.bread(), "lotId", bad, "productionRunId", runId, "defectLogIds", List.of(defect)));
        assertThat(ncr.path("status").asText()).isEqualTo("open");
        assertThat(ncr.path("lotId").asText()).isEqualTo(bad);
        assertThat(ncr.path("productionRunId").asText()).isEqualTo(runId);
        assertThat(ncr.path("severity").asText()).isEqualTo("major");

        // Finishing the run and completing the order leave nothing reserved.
        JsonNode finished = ok(f.editor(), post(runPath + "/finish"), Map.of("actualOutputQty", 20));
        assertThat(finished.path("runStatus").asText()).isEqualTo("finished");
        ok(f.editor(), post(orderPath + "/complete"), null);
        assertThat(jdbc.queryForObject("select coalesce(sum(reserved_quantity),0) from inventory where project_id=?", BigDecimal.class,
                f.project())).isEqualByComparingTo("0");

        // Genealogy runs both ways through the run, and the cost is the flour taken at its price.
        JsonNode back = ok(f.viewer(), get("/lots/" + good + "/trace").param("direction", "backward"), null);
        assertThat(lotIds(back)).contains(soon, late);
        JsonNode forward = ok(f.viewer(), get("/lots/" + soon + "/trace").param("direction", "forward"), null);
        assertThat(lotIds(forward)).contains(good, bad);
        assertThat(ok(f.viewer(), get("/lots/" + good), null).path("productionRunId").asText()).isEqualTo(runId);
        JsonNode cost = ok(f.viewer(), get(runPath + "/cost"), null);
        amount("20", cost.path("materialCost"));
        amount("20", cost.path("outputQuantity"));
        amount("1", cost.path("costPerUnit"));
        assertThat(cost.path("costComplete").asBoolean()).isTrue();

        // Every quantity on hand is explained by the ledger, per item.
        for (Map.Entry<String, String> item : Map.of(f.flour(), "6", f.bread(), "20").entrySet()) {
            assertThat(jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory where item_id=?", BigDecimal.class,
                    item.getKey())).isEqualByComparingTo(item.getValue());
            assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta),0) from inventory_transaction where item_id=?",
                    BigDecimal.class, item.getKey())).isEqualByComparingTo(item.getValue());
        }
        amount("12", ok(f.viewer(), get("/lots/" + good), null).path("quantityOnHand"));
        amount("8", ok(f.viewer(), get("/lots/" + bad), null).path("quantityOnHand"));
    }

    @Test
    void refusedRolesShortStockWrongLotsAndAFinishedRunChangeNothingAndCancellingGivesTheStockBack() throws Exception {
        Fixture f = fixture();
        String soon = rawLot(f, "SOON", LocalDate.now().plusDays(10), "6");
        String late = rawLot(f, "LATE", LocalDate.now().plusDays(60), "10");
        String soonStock = stockOf(f, soon);
        String lateStock = stockOf(f, late);
        String bom = approvedBom(f);
        String salt = ok(f.owner(), post("/items"), Map.of("projectId", f.project(), "itemCode", "PQ-SALT-" + f.tag(),
                "itemName", "Salt " + f.tag(), "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")).path("itemId").asText();
        String saltLot = ok(f.editor(), post("/lots"), Map.of("projectId", f.project(), "itemId", salt, "lotNo", "PQ-SALT-" + f.tag()))
                .path("lotId").asText();
        String saltStock = ok(f.editor(), post("/inventories"), Map.of("projectId", f.project(), "itemId", salt, "lotId", saltLot,
                "location", "RAW", "quantity", 5)).path("inventoryId").asText();

        // 40 loaves need 20 kg and 16 are on hand: the order is told what is short, and cancelling it gives back what it held.
        String big = "/work-orders/" + ok(f.editor(), post("/work-orders"), workOrder(f, bom, "40")).path("workOrderId").asText();
        ok(f.owner(), post(big + "/approve"), null);
        JsonNode bigNeed = only(ok(f.editor(), get(big + "/readiness"), null).path("materials"), "itemId", f.flour());
        amount("20", bigNeed.path("requiredQuantity"));
        amount("4", bigNeed.path("shortageQuantity"));
        Map<String, Object> beforeAllocation = snapshot(f);
        call(f.viewer(), post(big + "/allocations"), Map.of(), 403);
        assertThat(snapshot(f)).isEqualTo(beforeAllocation);
        JsonNode short4 = ok(f.editor(), post(big + "/allocations"), Map.of());
        amount("16", short4.path("plan").get(0).path("allocatedNow"));
        amount("4", short4.path("plan").get(0).path("shortage"));
        stock(soonStock, "6", "6");
        stock(lateStock, "10", "10");
        call(f.editor(), post(big + "/cancel"), null, 403);
        ok(f.owner(), post(big + "/cancel"), null);
        stock(soonStock, "6", "0");
        stock(lateStock, "10", "0");
        assertThat(ok(f.viewer(), get(big + "/allocations"), null).path("allocations"))
                .allSatisfy(row -> assertThat(row.path("status").asText()).isEqualTo("closed"));

        // The order that fits is allocated and run.
        JsonNode order = ok(f.editor(), post("/work-orders"), workOrder(f, bom, "20"));
        String orderId = order.path("workOrderId").asText();
        String orderPath = "/work-orders/" + orderId;
        ok(f.owner(), post(orderPath + "/approve"), null);
        ok(f.editor(), post(orderPath + "/allocations"), Map.of());
        stock(soonStock, "6", "6");
        stock(lateStock, "10", "4");
        Map<String, Object> start = Map.of("projectId", f.project(), "workflowId", f.workflow(), "workOrderId", orderId,
                "plannedOutputQty", 20);
        Map<String, Object> beforeRun = snapshot(f);
        call(f.viewer(), post("/production-runs/start"), start, 403);
        call(f.editor(), post("/production-runs/start"), Map.of("projectId", f.project(), "workflowId", f.workflow(),
                "workOrderId", big.substring("/work-orders/".length()), "plannedOutputQty", 20), 400);
        assertThat(snapshot(f)).isEqualTo(beforeRun);
        String runId = ok(f.editor(), post("/production-runs/start"), start).path("productionRunId").asText();
        String runPath = "/production-runs/" + runId;

        // Refused inputs: a viewer, more than the LOT holds, and a LOT of another item offered as flour.
        Map<String, Object> ready = snapshot(f);
        call(f.viewer(), post(runPath + "/items"), input(f.flour(), soonStock, "1"), 403);
        call(f.editor(), post(runPath + "/items"), input(f.flour(), soonStock, "7"), 409);
        call(f.editor(), post(runPath + "/items"), input(f.flour(), saltStock, "1"), 400);
        call("unrelated-user", get(runPath), null, 403);
        assertThat(snapshot(f)).as("No refused input may move stock, use an allocation or record anything").isEqualTo(ready);

        // Cancelling an input gives its stock back: to the order when asked (R4), otherwise to free stock (R2).
        String taken = record(f, runId, f.flour(), soonStock, "input", "6", "kg");
        stock(soonStock, "0", "0");
        call(f.viewer(), post(runPath + "/items/" + taken + "/cancel"), Map.of("reason", "Viewer cannot cancel"), 403);
        ok(f.editor(), post(runPath + "/items/" + taken + "/cancel"), Map.of("reason", "Weighed on the wrong scale", "reallocate", true));
        stock(soonStock, "6", "6");
        List<JsonNode> reallocated = elements(ok(f.viewer(), get(orderPath + "/allocations"), null).path("allocations")).stream()
                .filter(row -> soon.equals(row.path("lotId").asText()) && "open".equals(row.path("status").asText())).toList();
        assertThat(reallocated).hasSize(1);
        amount("6", reallocated.get(0).path("remaining"));
        String late4 = record(f, runId, f.flour(), lateStock, "input", "4", "kg");
        stock(lateStock, "6", "0");
        ok(f.editor(), post(runPath + "/items/" + late4 + "/cancel"), Map.of("reason", "Counted twice"));
        stock(lateStock, "10", "0");
        record(f, runId, f.flour(), soonStock, "input", "6", "kg");
        record(f, runId, f.flour(), lateStock, "input", "4", "kg");
        stock(soonStock, "0", "0");
        stock(lateStock, "6", "0");
        String loaves = productLot(f, "LOAVES");
        record(f, runId, f.bread(), stockOf(f, loaves), "output", "20", "ea");
        assertThat(jdbc.queryForObject("select count(*) from lot_trace where production_run_id=?", Integer.class, runId))
                .as("The cancelled input leaves no genealogy behind").isEqualTo(2);

        // Nothing changes a finished run through the recording API; corrections have their own approval path.
        ok(f.editor(), post(runPath + "/finish"), Map.of("actualOutputQty", 20));
        Map<String, Object> finished = snapshot(f);
        call(f.editor(), post(runPath + "/items"), input(f.flour(), lateStock, "1"), 400);
        call(f.editor(), post(runPath + "/items/" + late4 + "/cancel"), Map.of("reason", "Too late"), 400);
        call(f.editor(), post(runPath + "/finish"), Map.of("actualOutputQty", 21), 400);
        assertThat(snapshot(f)).isEqualTo(finished);
        ok(f.editor(), post(orderPath + "/complete"), null);
        assertThat(jdbc.queryForObject("select coalesce(sum(reserved_quantity),0) from inventory where project_id=?", BigDecimal.class,
                f.project())).isEqualByComparingTo("0");
        for (Map.Entry<String, String> item : Map.of(f.flour(), "6", f.bread(), "20", salt, "5").entrySet()) {
            assertThat(jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory where item_id=?", BigDecimal.class,
                    item.getKey())).isEqualByComparingTo(item.getValue());
            assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta),0) from inventory_transaction where item_id=?",
                    BigDecimal.class, item.getKey())).isEqualByComparingTo(item.getValue());
        }
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String owner = user();
        String editor = user();
        String viewer = user();
        String project = ok(owner, post("/projects"), Map.of("projectName", "Production quality " + tag, "ownerId", owner))
                .path("projectId").asText();
        invite(owner, editor, project, "editor");
        invite(owner, viewer, project, "viewer");
        for (String place : List.of("RAW", "LINE", "FG")) {
            ok(owner, post("/storage-locations"), Map.of("projectId", project, "locationCode", place, "locationType", "location"));
        }
        String flour = ok(owner, post("/items"), Map.of("projectId", project, "itemCode", "PQ-FLOUR-" + tag, "itemName", "Flour " + tag,
                "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y", "unitCost", new BigDecimal("2"))).path("itemId").asText();
        String bread = ok(owner, post("/items"), Map.of("projectId", project, "itemCode", "PQ-BREAD-" + tag, "itemName", "Bread " + tag,
                "itemType", "product", "unitId", "unit_ea", "lotManageYn", "Y", "lotReleaseRequiredYn", "Y")).path("itemId").asText();
        String standard = ok(owner, post("/inspection-standards"), Map.of("projectId", project, "itemId", bread,
                "inspectionType", "Core temperature", "stage", "receipt", "standardMax", 50, "required", true)).path("standardId").asText();
        String workflow = ok(owner, post("/workflows"), Map.of("projectId", project, "workflowName", "Bake " + tag))
                .path("workflowId").asText();
        return new Fixture(tag, owner, editor, viewer, project, flour, bread, standard, workflow);
    }

    private String approvedBom(Fixture f) throws Exception {
        String bom = ok(f.editor(), post("/boms"), Map.of("projectId", f.project(), "targetItemId", f.bread(),
                "bomName", "Bread " + f.tag(), "baseQuantity", 10, "baseUnit", "ea")).path("bomId").asText();
        ok(f.editor(), post("/boms/" + bom + "/lines"), Map.of("childItemId", f.flour(), "quantity", 5, "unit", "kg"));
        ok(f.editor(), post("/boms/" + bom + "/submit"), null);
        call(f.editor(), post("/boms/" + bom + "/approve"), null, 403);
        ok(f.owner(), post("/boms/" + bom + "/approve"), null);
        return bom;
    }

    private String rawLot(Fixture f, String name, LocalDate expiry, String quantity) throws Exception {
        String lot = ok(f.editor(), post("/lots"), Map.of("projectId", f.project(), "itemId", f.flour(),
                "lotNo", "PQ-" + name + "-" + f.tag(), "expiryDate", expiry.toString())).path("lotId").asText();
        ok(f.editor(), post("/inventories"), Map.of("projectId", f.project(), "itemId", f.flour(), "lotId", lot,
                "location", "RAW", "quantity", new BigDecimal(quantity)));
        return lot;
    }

    private String productLot(Fixture f, String name) throws Exception {
        String lot = ok(f.editor(), post("/lots"), Map.of("projectId", f.project(), "itemId", f.bread(),
                "lotNo", "PQ-" + name + "-" + f.tag())).path("lotId").asText();
        ok(f.editor(), post("/inventories"), Map.of("projectId", f.project(), "itemId", f.bread(), "lotId", lot,
                "location", "FG", "quantity", 0));
        return lot;
    }

    private String stockOf(Fixture f, String lot) {
        return jdbc.queryForObject("select inventory_id from inventory where project_id=? and lot_id=?", String.class, f.project(), lot);
    }

    private String record(Fixture f, String run, String item, String inventory, String direction, String quantity, String unit)
            throws Exception {
        return ok(f.editor(), post("/production-runs/" + run + "/items"), Map.of("itemId", item, "inventoryId", inventory,
                "direction", direction, "plannedQty", new BigDecimal(quantity), "actualQty", new BigDecimal(quantity), "unit", unit))
                .path("productionRunItemId").asText();
    }

    private static Map<String, Object> workOrder(Fixture f, String bom, String quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", f.project());
        body.put("workflowId", f.workflow());
        body.put("workOrderTitle", "Bake " + f.tag());
        body.put("targetItemId", f.bread());
        body.put("targetQuantity", new BigDecimal(quantity));
        body.put("bomId", bom);
        return body;
    }

    private static Map<String, Object> inspection(Fixture f, String run, String lot, String measured) {
        return Map.of("projectId", f.project(), "productionRunId", run, "lotId", lot, "inspectionType", "Core temperature",
                "measuredValue", new BigDecimal(measured), "standardId", f.standard());
    }

    private static Map<String, Object> input(String item, String inventory, String quantity) {
        return Map.of("itemId", item, "inventoryId", inventory, "direction", "input", "plannedQty", new BigDecimal(quantity),
                "actualQty", new BigDecimal(quantity), "unit", "kg");
    }

    private static Map<String, Object> issue(String inventory, String quantity) {
        return Map.of("inventoryId", inventory, "transactionType", "issue", "quantity", new BigDecimal(quantity),
                "requestId", UUID.randomUUID().toString());
    }

    private void reserved(String inventory, String quantity) {
        assertThat(jdbc.queryForObject("select reserved_quantity from inventory where inventory_id=?", BigDecimal.class, inventory))
                .isEqualByComparingTo(quantity);
    }

    private void stock(String inventory, String quantity, String reserved) {
        assertThat(jdbc.queryForObject("select quantity from inventory where inventory_id=?", BigDecimal.class, inventory))
                .as("quantity of " + inventory).isEqualByComparingTo(quantity);
        reserved(inventory, reserved);
    }

    private static List<String> lotIds(JsonNode trace) {
        List<String> ids = new ArrayList<>();
        trace.path("nodes").forEach(node -> ids.add(node.path("lot").path("lotId").asText()));
        return ids;
    }

    private static JsonNode only(JsonNode rows, String field, String value) {
        List<JsonNode> matches = elements(rows).stream().filter(row -> value.equals(row.path(field).asText())).toList();
        assertThat(matches).as(rows.toString()).hasSize(1);
        return matches.get(0);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private void invite(String owner, String member, String project, String role) throws Exception {
        String token = ok(owner, post("/project-invites"), Map.of("projectId", project, "invitedEmail", member + "@test.local",
                "projectRole", role)).path("inviteToken").asText();
        assertThat(ok(member, post("/project-invites/accept"), Map.of("inviteToken", token)).path("userId").asText()).isEqualTo(member);
    }

    private Map<String, Object> stockAndLedger(Fixture f) {
        return Map.of("stock", jdbc.queryForList("select inventory_id,quantity,reserved_quantity,available_quantity,lot_id,location,"
                        + "inventory_status from inventory where project_id=? order by inventory_id", f.project()),
                "ledger", jdbc.queryForList("select inventory_transaction_id,inventory_id,quantity_delta,transaction_type,reference_type,"
                        + "reference_id,lot_id from inventory_transaction where project_id=? order by inventory_transaction_id", f.project()));
    }

    private Map<String, Object> snapshot(Fixture f) {
        Map<String, Object> result = new HashMap<>(stockAndLedger(f));
        result.put("orders", jdbc.queryForList("select work_order_id,work_order_status from work_order where project_id=? "
                + "order by work_order_id", f.project()));
        result.put("allocations", jdbc.queryForList("select a.* from stock_allocation a join work_order o using(work_order_id) "
                + "where o.project_id=? order by allocation_id", f.project()));
        result.put("runs", jdbc.queryForList("select production_run_id,run_status,actual_output_qty from production_run "
                + "where project_id=? order by production_run_id", f.project()));
        result.put("items", jdbc.queryForList("select i.* from production_run_item i join production_run r using(production_run_id) "
                + "where r.project_id=? order by production_run_item_id", f.project()));
        result.put("lots", jdbc.queryForList("select lot_id,lot_status,production_run_id from lot_master where project_id=? "
                + "order by lot_id", f.project()));
        result.put("trace", jdbc.queryForList("select t.* from lot_trace t join production_run r using(production_run_id) "
                + "where r.project_id=? order by 1", f.project()));
        return result;
    }

    private int ledgerRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?", Integer.class, f.project());
    }

    private JsonNode ok(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(user, request, body, 200);
    }

    private JsonNode call(String user, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).as(envelope.path("message").asText()).isEqualTo(expected);
        return envelope.path("data");
    }

    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>();
        array.forEach(rows::add);
        return rows;
    }

    private static void amount(String expected, JsonNode actual) {
        assertThat(actual.isNumber()).as(actual.toString()).isTrue();
        assertThat(actual.decimalValue()).isEqualByComparingTo(expected);
    }

    private record Fixture(String tag, String owner, String editor, String viewer, String project, String flour, String bread,
            String standard, String workflow) {}
}
