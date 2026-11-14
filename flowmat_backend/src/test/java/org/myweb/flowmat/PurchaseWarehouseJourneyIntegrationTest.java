package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
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

/** The purchase-to-warehouse journey through real JWT filters and MockMvc, with real Postgres and Redis. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PurchaseWarehouseJourneyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void approvedLotStockIsReceivedPutAwayAndReturnedWithAReconciledLedger() throws Exception {
        OffsetDateTime from = OffsetDateTime.now().minusMinutes(1);
        Fixture f = fixture();
        JsonNode draft = ok(f.editor(), post("/purchase-orders"), order(f, line(f.material(), "10")));
        String path = orderPath(draft);
        String line = draft.path("lines").get(0).path("lineId").asText();
        assertThat(draft.path("needsApproval").asBoolean()).isTrue();
        Map<String, Object> beforeApproval = snapshot(f);
        call(f.editor(), post(path + "/order"), null, 409);
        call(f.editor(), post(path + "/approve"), null, 403);
        assertThat(snapshot(f)).isEqualTo(beforeApproval);
        assertThat(ok(f.owner(), post(path + "/approve"), null).path("approvedBy").asText()).isEqualTo(f.owner());
        assertThat(ok(f.editor(), post(path + "/order"), null).path("status").asText()).isEqualTo("ordered");

        String lotNo = "JOURNEY-" + f.tag();
        Map<String, Object> first = receipt(UUID.randomUUID(), arrived(line, "4", "DOCK", lotNo));
        JsonNode received = ok(f.editor(), post(path + "/receipts"), first);
        String dock = received.path("receipts").get(0).path("inventoryId").asText();
        String firstReceipt = received.path("receipts").get(0).path("receiptId").asText();
        Map<String, Object> afterFirst = snapshot(f);
        assertThat(ok(f.editor(), post(path + "/receipts"), first).path("receipts").size()).isEqualTo(1);
        assertThat(snapshot(f)).isEqualTo(afterFirst);
        Map<String, Object> different = new HashMap<>(first);
        different.put("lines", List.of(arrived(line, "1", "DOCK", lotNo)));
        call(f.editor(), post(path + "/receipts"), different, 409);
        assertThat(snapshot(f)).isEqualTo(afterFirst);

        received = ok(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(line, "2", "DOCK", lotNo)));
        amount("6", received.path("lines").get(0).path("receivedQuantity"));
        amount("4", received.path("lines").get(0).path("remainingQuantity"));
        assertThat(received.path("status").asText()).isEqualTo("ordered");
        String lot = ok(f.viewer(), get("/inventories/" + dock), null).path("lotId").asText();
        assertThat(lot).isNotBlank();
        Map<String, Object> beforePlan = stockAndLedger(f);
        JsonNode planned = ok(f.editor(), post("/warehouse-tasks"), Map.of("projectId", f.project(), "inventoryId", dock,
                "quantity", new BigDecimal("6"), "toLocation", "SHELF"));
        assertThat(planned.path("status").asText()).isEqualTo("open");
        assertThat(planned.path("lotId").asText()).isEqualTo(lot);
        assertThat(stockAndLedger(f)).as("Planning a putaway cannot move stock or append ledger movements").isEqualTo(beforePlan);
        String taskPath = "/warehouse-tasks/" + planned.path("taskId").asText();
        JsonNode done = ok(f.editor(), post(taskPath + "/complete"), Map.of("expectedToLocation", "SHELF"));
        assertThat(done.path("status").asText()).isEqualTo("done");
        String transfer = done.path("transferId").asText();
        List<JsonNode> legs = elements(ok(f.viewer(), get("/inventory-transactions").param("projectId", f.project()), null)).stream()
                .filter(row -> transfer.equals(row.path("referenceId").asText())).toList();
        assertThat(legs).hasSize(2);
        JsonNode out = legs.stream().filter(row -> "transfer_out".equals(row.path("transactionType").asText())).findFirst().orElseThrow();
        JsonNode in = legs.stream().filter(row -> "transfer_in".equals(row.path("transactionType").asText())).findFirst().orElseThrow();
        amount("-6", out.path("quantityDelta"));
        amount("6", in.path("quantityDelta"));
        assertThat(out.path("inventoryId").asText()).isEqualTo(dock);
        String shelf = in.path("inventoryId").asText();
        assertThat(shelf).isNotEqualTo(dock);
        for (JsonNode leg : legs) {
            assertThat(leg.path("referenceType").asText()).isEqualTo("inventory_transfer");
            assertThat(leg.path("lotId").asText()).isEqualTo(lot);
        }
        amount("0", ok(f.viewer(), get("/inventories/" + dock), null).path("quantity"));
        amount("6", ok(f.viewer(), get("/inventories/" + shelf), null).path("quantity"));
        Map<String, Object> afterMove = snapshot(f);
        call(f.editor(), post(taskPath + "/complete"), Map.of("expectedToLocation", "SHELF"), 409);
        assertThat(snapshot(f)).isEqualTo(afterMove);

        JsonNode finished = ok(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(line, "4", "DOCK", lotNo)));
        assertThat(finished.path("status").asText()).isEqualTo("received");
        assertThat(finished.path("receipts").size()).isEqualTo(3);
        assertThat(finished.path("receipts").get(0).path("receiptId").asText()).isEqualTo(firstReceipt);
        assertThat(elements(finished.path("receipts"))).allSatisfy(row -> assertThat(row.path("inventoryId").asText()).isEqualTo(dock));
        assertThat(ok(f.viewer(), get("/inventories/" + shelf), null).path("lotId").asText()).isEqualTo(lot);
        assertThat(jdbc.queryForObject("""
                select count(*) from purchase_receipt r join inventory_transaction m
                  on m.reference_type='purchase_receipt' and m.reference_id=r.receipt_id
                 where r.purchase_order_id=? and m.inventory_id=r.inventory_id and m.lot_id=?
                """, Integer.class, finished.path("purchaseOrderId").asText(), lot)).isEqualTo(3);

        Map<String, Object> back = Map.of("requestId", UUID.randomUUID().toString(), "lineId", line, "inventoryId", shelf,
                "quantity", new BigDecimal("2"), "reason", "Damaged bags", "replacement", false);
        JsonNode returned = ok(f.editor(), post(path + "/returns"), back);
        JsonNode one = returned.path("returns").get(0);
        assertThat(one.path("inventoryId").asText()).isEqualTo(shelf);
        assertThat(one.path("lineId").asText()).isEqualTo(line);
        assertThat(one.path("lotNo").asText()).isEqualTo(lotNo);
        amount("10", returned.path("lines").get(0).path("receivedQuantity"));
        amount("2", returned.path("lines").get(0).path("returnedQuantity"));
        amount("0", returned.path("lines").get(0).path("remainingQuantity"));
        assertThat(returned.path("status").asText()).isEqualTo("received");
        Map<String, Object> afterReturn = snapshot(f);
        assertThat(ok(f.editor(), post(path + "/returns"), back).path("returns").get(0).path("returnId").asText())
                .isEqualTo(one.path("returnId").asText());
        assertThat(snapshot(f)).isEqualTo(afterReturn);
        Map<String, Object> changedReturn = new HashMap<>(back);
        changedReturn.put("quantity", new BigDecimal("1"));
        call(f.editor(), post(path + "/returns"), changedReturn, 409);
        assertThat(snapshot(f)).isEqualTo(afterReturn);

        JsonNode ledger = ok(f.viewer(), get("/inventory-transactions").param("projectId", f.project()), null);
        assertThat(elements(ledger)).hasSize(7);
        // Creating the receipt's stock record also writes its zero opening balance.
        assertThat(elements(ledger).stream().filter(row -> row.path("quantityDelta").decimalValue().signum() == 0).toList())
                .singleElement().satisfies(row -> {
                    assertThat(row.path("referenceType").asText()).isEqualTo("inventory");
                    assertThat(row.path("inventoryId").asText()).isEqualTo(dock);
                });
        JsonNode correction = elements(ledger).stream().filter(row -> "purchase_return".equals(row.path("referenceType").asText()))
                .findFirst().orElseThrow();
        assertThat(correction.path("transactionType").asText()).isEqualTo("adjustment");
        assertThat(correction.path("referenceId").asText()).isEqualTo(one.path("returnId").asText());
        assertThat(correction.path("inventoryId").asText()).isEqualTo(shelf);
        amount("-2", correction.path("quantityDelta"));
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory where project_id=?", BigDecimal.class,
                f.project())).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity_delta),0) from inventory_transaction where project_id=?",
                BigDecimal.class, f.project())).isEqualByComparingTo("8");
        amount("4", ok(f.viewer(), get("/inventories/" + dock), null).path("quantity"));
        amount("4", ok(f.viewer(), get("/inventories/" + shelf), null).path("quantity"));
        amount("8", ok(f.viewer(), get("/lots/" + lot), null).path("quantityOnHand"));
        JsonNode summary = ok(f.viewer(), get("/stock-movement-summary").param("projectId", f.project())
                .param("from", from.toString()).param("to", OffsetDateTime.now().plusSeconds(1).toString()), null).path("lines");
        assertThat(summary.size()).isEqualTo(1);
        for (Map.Entry<String, String> total : Map.of("opening", "0", "received", "10", "transferred", "0", "corrected", "-2",
                "closing", "8", "unexplained", "0", "issued", "0", "consumed", "0").entrySet()) {
            amount(total.getValue(), summary.get(0).path(total.getKey()));
        }
    }

    @Test
    void refusedRolesLotsQuantitiesAndASecondInvalidLocationLeaveTheWholeJourneyUnchanged() throws Exception {
        Fixture f = fixture();
        JsonNode draft = ok(f.editor(), post("/purchase-orders"), order(f, line(f.material(), "10"), line(f.otherMaterial(), "5")));
        String path = orderPath(draft);
        String firstLine = draft.path("lines").get(0).path("lineId").asText();
        String secondLine = draft.path("lines").get(1).path("lineId").asText();
        Map<String, Object> waiting = snapshot(f);
        call(f.viewer(), put("/purchase-settings"), setting(f.project()), 403);
        call(f.editor(), put("/purchase-settings"), setting(f.project()), 403);
        call(f.viewer(), post(path + "/approve"), null, 403);
        call(f.editor(), post(path + "/approve"), null, 403);
        call(f.viewer(), post(path + "/order"), null, 403);
        call(f.editor(), post(path + "/order"), null, 409);
        assertThat(snapshot(f)).isEqualTo(waiting);
        ok(f.owner(), post(path + "/approve"), null);
        ok(f.editor(), post(path + "/order"), null);
        String wrongLot = "AUX-" + f.tag();
        ok(f.owner(), post("/lots"), Map.of("projectId", f.project(), "itemId", f.otherMaterial(), "lotNo", wrongLot,
                "expiryDate", "2100-12-31"));
        Map<String, Object> before = snapshot(f);
        call(f.viewer(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(firstLine, "1", "DOCK", "LOT-VIEWER")), 403);
        call(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(firstLine, "11", "DOCK", "LOT-OVER")), 400);
        call(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), Map.of("lineId", firstLine, "quantity", 1, "location", "DOCK")), 400);
        call(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(firstLine, "1", "DOCK", wrongLot)), 400);
        assertThat(snapshot(f)).isEqualTo(before);

        // The first line can create a LOT, stock and movement before the invalid second line is discovered.
        call(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(), arrived(firstLine, "2", "DOCK", "ROLLBACK-" + f.tag()),
                arrived(secondLine, "1", "NOT-LISTED", wrongLot)), 400);
        assertThat(snapshot(f)).as("No first-line stock, receipt, LOT or ledger may survive a later bad location").isEqualTo(before);
        JsonNode received = ok(f.editor(), post(path + "/receipts"), receipt(UUID.randomUUID(),
                arrived(firstLine, "4", "DOCK", "GOOD-" + f.tag()), arrived(secondLine, "2", "DOCK", wrongLot)));
        String stock = received.path("receipts").get(0).path("inventoryId").asText();
        String taskPath = "/warehouse-tasks/" + ok(f.editor(), post("/warehouse-tasks"), Map.of("projectId", f.project(),
                "inventoryId", stock, "quantity", 2, "toLocation", "SHELF")).path("taskId").asText();
        Map<String, Object> afterPlan = snapshot(f);
        call(f.viewer(), post(taskPath + "/complete"), null, 403);
        call(f.viewer(), post(path + "/returns"), Map.of("requestId", UUID.randomUUID().toString(), "lineId", firstLine,
                "inventoryId", stock, "quantity", 1, "reason", "Viewer cannot return"), 403);
        call("unrelated-user", get(path), null, 403);
        assertThat(ok(f.viewer(), get(path), null).path("receipts").size()).isEqualTo(2);
        assertThat(snapshot(f)).isEqualTo(afterPlan);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String owner = user();
        String editor = user();
        String viewer = user();
        String project = ok(owner, post("/projects"), Map.of("projectName", "Purchase warehouse " + tag, "ownerId", owner))
                .path("projectId").asText();
        invite(owner, editor, project, "editor");
        invite(owner, viewer, project, "viewer");
        String material = item(owner, project, "J-MAIN-" + tag);
        String other = item(owner, project, "J-AUX-" + tag);
        for (String place : List.of("DOCK", "SHELF")) {
            ok(owner, post("/storage-locations"), Map.of("projectId", project, "locationCode", place, "locationType", "location"));
        }
        ok(owner, put("/purchase-settings"), setting(project));
        return new Fixture(tag, owner, editor, viewer, project, material, other);
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

    private String item(String owner, String project, String code) throws Exception {
        return ok(owner, post("/items"), Map.of("projectId", project, "itemCode", code, "itemName", code,
                "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")).path("itemId").asText();
    }

    private Map<String, Object> stockAndLedger(Fixture f) {
        return Map.of("stock", jdbc.queryForList("select inventory_id,quantity,reserved_quantity,available_quantity,lot_id,location "
                        + "from inventory where project_id=? order by inventory_id", f.project()),
                "ledger", jdbc.queryForList("select inventory_transaction_id,inventory_id,quantity_delta,transaction_type,reference_type,reference_id,lot_id "
                        + "from inventory_transaction where project_id=? order by inventory_transaction_id", f.project()));
    }

    private Map<String, Object> snapshot(Fixture f) {
        Map<String, Object> result = new HashMap<>(stockAndLedger(f));
        result.put("orders", jdbc.queryForList("select purchase_order_id,status,approved_by,approved_at from purchase_order where project_id=? order by purchase_order_id", f.project()));
        result.put("lines", jdbc.queryForList("select l.* from purchase_order_line l join purchase_order p using(purchase_order_id) where p.project_id=? order by line_id", f.project()));
        result.put("receipts", jdbc.queryForList("select r.* from purchase_receipt r join purchase_order p using(purchase_order_id) where p.project_id=? order by receipt_id", f.project()));
        result.put("returns", jdbc.queryForList("select r.* from purchase_return r join purchase_order p using(purchase_order_id) where p.project_id=? order by return_id", f.project()));
        result.put("lots", jdbc.queryForList("select lot_id,item_id,lot_no from lot_master where project_id=? order by lot_id", f.project()));
        result.put("tasks", jdbc.queryForList("select task_id,status,quantity,transfer_id from warehouse_task where project_id=? order by task_id", f.project()));
        return result;
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

    private static Map<String, Object> setting(String project) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", project);
        body.put("approvalLimit", new BigDecimal("100"));
        body.put("expectedVersion", null);
        return body;
    }

    @SafeVarargs
    private static Map<String, Object> order(Fixture f, Map<String, Object>... lines) {
        return Map.of("projectId", f.project(), "supplierName", "Journey supplier", "lines", Arrays.asList(lines));
    }

    private static Map<String, Object> line(String item, String quantity) {
        return Map.of("itemId", item, "quantity", new BigDecimal(quantity), "unitPrice", new BigDecimal("20"));
    }

    @SafeVarargs
    private static Map<String, Object> receipt(UUID key, Map<String, Object>... lines) {
        return Map.of("requestId", key.toString(), "lines", Arrays.asList(lines));
    }

    private static Map<String, Object> arrived(String line, String quantity, String location, String lot) {
        return Map.of("lineId", line, "quantity", new BigDecimal(quantity), "location", location, "lotNo", lot,
                "expiryDate", "2100-12-31");
    }

    private static String orderPath(JsonNode order) { return "/purchase-orders/" + order.path("purchaseOrderId").asText(); }

    private static List<JsonNode> elements(JsonNode array) {
        var rows = new java.util.ArrayList<JsonNode>();
        array.forEach(rows::add);
        return rows;
    }

    private static void amount(String expected, JsonNode actual) {
        assertThat(actual.isNumber()).as(actual.toString()).isTrue();
        assertThat(actual.decimalValue()).isEqualByComparingTo(expected);
    }

    private record Fixture(String tag, String owner, String editor, String viewer, String project, String material, String otherMaterial) {}
}
