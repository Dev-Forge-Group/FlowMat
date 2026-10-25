package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A purchase return names the NCR that decided to send the stock back (docs/domain/purchasing.md PO42), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseReturnNcrIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aReturnNamesAnOpenNcrToReturnToSupplierOfItsItemAndLot() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(post("/projects"), Map.of("projectName", "Return NCR " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = item(project, "RN-FLOUR-" + tag, "N");
        String yeast = item(project, "RN-YEAST-" + tag, "Y");
        // PO-0001 brings flour 10 and yeast LOT L-1; PO-0002 brings yeast LOT L-9.
        JsonNode first = received(project, List.of(Map.of("itemId", flour, "quantity", 10), Map.of("itemId", yeast, "quantity", 5)),
            List.of(receiving(0, 10, null), receiving(1, 5, "L-1")));
        JsonNode second = received(project, List.of(Map.of("itemId", yeast, "quantity", 3)), List.of(receiving(0, 3, "L-9")));
        String path = "/purchase-orders/" + first.path("purchaseOrderId").asText();
        String yeastLine = first.path("lines").get(1).path("lineId").asText();
        String yeastStock = first.path("receipts").get(1).path("inventoryId").asText();
        String lot = lotOf(yeastStock);
        String otherLot = lotOf(second.path("receipts").get(0).path("inventoryId").asText());

        String sour = ncr(project, "NCR-0001", "open", "return_to_supplier", yeast, lot);
        String pending = ncr(project, "NCR-0002", "open", "pending", yeast, lot);
        String closed = ncr(project, "NCR-0003", "closed", "return_to_supplier", yeast, lot);
        String flourNcr = ncr(project, "NCR-0004", "open", "return_to_supplier", flour, null);
        String l9 = ncr(project, "NCR-0005", "open", "return_to_supplier", yeast, otherLot);
        String elsewhere = ncr(DEMO_PROJECT, "NCR-" + tag, "open", "return_to_supplier", null, null);

        // Only an open NCR of this project, set to return to supplier, about this item and the LOT going back.
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", pending), 409);
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", closed), 409);
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", flourNcr), 400);
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", l9), 400);
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", elsewhere), 400);
        call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Sour", "missing"), 400);
        assertValue("5", call(get("/inventories/" + yeastStock), null, 200).path("quantity"));

        Map<String, Object> linked = back(yeastLine, yeastStock, "2", "Sour", sour);
        JsonNode returned = call(post(path + "/returns"), linked, 200);
        JsonNode one = returned.path("returns").get(0);
        assertEquals(sour, one.path("nonconformityId").asText(), one.toString());
        assertEquals("NCR-0001", one.path("ncrNo").asText());
        assertEquals("NCR-0001", jdbc.queryForObject("select ncr_no from purchase_return where return_id=?", String.class,
            one.path("returnId").asText()));
        assertValue("3", call(get("/inventories/" + yeastStock), null, 200).path("quantity"));

        // The same request again changes nothing; the same key without the NCR is another return and is refused.
        assertEquals(1, call(post(path + "/returns"), linked, 200).path("returns").size());
        Map<String, Object> unnamed = new HashMap<>(linked);
        unnamed.remove("nonconformityId");
        call(post(path + "/returns"), unnamed, 409);

        // A return without an NCR goes back as before and names none.
        JsonNode plain = call(post(path + "/returns"), back(yeastLine, yeastStock, "1", "Short weight", null), 200);
        assertTrue(plain.path("returns").get(1).path("ncrNo").isNull(), plain.toString());
        assertTrue(plain.path("returns").get(1).path("nonconformityId").isNull(), plain.toString());
    }

    /** A new order of these lines, ordered and received as given. */
    private JsonNode received(String projectId, List<Map<String, Object>> lines, List<Map<String, Object>> receipts) throws Exception {
        JsonNode draft = call(post("/purchase-orders"), Map.of("projectId", projectId, "supplierName", "Mill Co", "lines", lines), 200);
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        call(post(path + "/order"), null, 200);
        List<Map<String, Object>> withLines = receipts.stream().map(receipt -> {
            Map<String, Object> line = new HashMap<>(receipt);
            line.put("lineId", draft.path("lines").get((Integer) line.remove("index")).path("lineId").asText());
            return line;
        }).toList();
        return call(post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(), "lines", withLines), 200);
    }

    private static Map<String, Object> receiving(int index, int quantity, String lotNo) {
        Map<String, Object> line = new HashMap<>();
        line.put("index", index);
        line.put("quantity", quantity);
        line.put("location", "WH-A");
        if (lotNo != null) {
            line.put("lotNo", lotNo);
            line.put("expiryDate", "2042-01-01");
        }
        return line;
    }

    private static Map<String, Object> back(String lineId, String inventoryId, String quantity, String reason, String nonconformityId) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("lineId", lineId);
        body.put("inventoryId", inventoryId);
        body.put("quantity", new BigDecimal(quantity));
        body.put("reason", reason);
        if (nonconformityId != null) body.put("nonconformityId", nonconformityId);
        return body;
    }

    /** An NCR of the project as quality keeps it; a closed one has its closing time. */
    private String ncr(String projectId, String ncrNo, String status, String disposition, String itemId, String lotId) {
        String id = UUID.randomUUID().toString();
        boolean open = "open".equals(status);
        jdbc.update("insert into nonconformity(nonconformity_id,project_id,ncr_no,title,severity,status,item_id,lot_id,disposition,"
                + "raised_by,raised_at,closed_by,closed_at) values(?,?,?,?,'major',?,?,?,?,?,now(),?,?)",
            id, projectId, ncrNo, "Sour yeast", status, itemId, lotId, disposition, DEMO_OWNER, open ? null : DEMO_OWNER,
            open ? null : Timestamp.from(Instant.now()));
        return id;
    }

    private String lotOf(String inventoryId) {
        return jdbc.queryForObject("select lot_id from inventory where inventory_id=?", String.class, inventoryId);
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private String item(String projectId, String code, String lotManageYn) throws Exception {
        return call(post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", lotManageYn), 200).path("itemId").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
