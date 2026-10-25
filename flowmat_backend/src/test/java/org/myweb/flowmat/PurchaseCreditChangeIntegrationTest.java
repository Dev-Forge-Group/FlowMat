package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A supplier credit kept for a return is changed or withdrawn with a reason (docs/domain/purchasing.md PO52), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseCreditChangeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aCreditIsChangedOrWithdrawnWithAReasonAndTheLatestStands() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Credit changes " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = call(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "CC-FLOUR-" + tag, "itemName", "flour",
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill Co",
            "lines", List.of(Map.of("itemId", flour, "quantity", 10, "unitPrice", new BigDecimal("2.5")))), 200);
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        String line = call(DEMO_OWNER, post(path + "/order"), null, 200).path("lines").get(0).path("lineId").asText();
        String stock = call(DEMO_OWNER, post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-A"))), 200).path("receipts").get(0).path("inventoryId").asText();
        String back = call(DEMO_OWNER, post(path + "/returns"), Map.of("requestId", UUID.randomUUID().toString(), "lineId", line,
            "inventoryId", stock, "quantity", 4, "reason", "Wet bags"), 200).path("returns").get(0).path("returnId").asText();
        String credit = path + "/returns/" + back + "/credit";

        // Only a credit that was kept changes, with a reason, by someone who can edit.
        call(DEMO_OWNER, put(credit), change("8", "CN-13", "Supplier sent less"), 409);
        Map<String, Object> first = Map.of("amount", new BigDecimal("10"), "creditNote", "CN-12");
        call(DEMO_OWNER, post(credit), first, 200);
        call(DEMO_OWNER, put(credit), change("8", "CN-13", " "), 400);
        call(DEMO_OWNER, put(credit), change("-1", null, "Typo"), 400);
        call(DEMO_OWNER, put(path + "/returns/missing/credit"), change("8", null, "Typo"), 404);
        call(member(project, "viewer"), put(credit), change("8", null, "Typo"), 403);

        // The latest change stands; the same again keeps one.
        JsonNode changed = call(DEMO_OWNER, put(credit), change("8", " CN-13 ", "Supplier sent less"), 200).path("returns").get(0);
        assertValue("8", changed.path("creditAmount"));
        assertEquals("CN-13", changed.path("creditNote").asText());
        assertEquals(DEMO_OWNER, changed.path("creditedBy").asText());
        assertEquals("Supplier sent less", changed.path("creditChanges").get(0).path("reason").asText());
        assertEquals(1, call(DEMO_OWNER, put(credit), change("8.0", "CN-13", "Again"), 200).path("returns").get(0).path("creditChanges").size());

        // Withdrawn, nothing is credited and the note goes; credited again later, it stands again.
        JsonNode withdrawn = call(DEMO_OWNER, put(credit), change(null, "CN-13", "Wrong return"), 200).path("returns").get(0);
        assertTrue(withdrawn.path("creditAmount").isNull(), withdrawn.toString());
        assertTrue(withdrawn.path("creditedAt").isNull(), withdrawn.toString());
        assertTrue(withdrawn.path("creditChanges").get(1).path("amount").isNull(), withdrawn.toString());
        assertTrue(withdrawn.path("creditChanges").get(1).path("creditNote").isNull(), withdrawn.toString());
        JsonNode again = call(DEMO_OWNER, put(credit), change("10", null, "Credited after all"), 200).path("returns").get(0);
        assertValue("10", again.path("creditAmount"));
        assertEquals(3, again.path("creditChanges").size());

        // The first credit sent again changes nothing; it stays as first kept.
        assertValue("10", call(DEMO_OWNER, post(credit), first, 200).path("returns").get(0).path("creditAmount"));
        assertEquals(3, jdbc.queryForObject("select count(*) from purchase_credit_change where return_id = ?", Integer.class, back));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject("select amount from purchase_credit where return_id = ?",
            BigDecimal.class, back)));
    }

    private static Map<String, Object> change(String amount, String creditNote, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount == null ? null : new BigDecimal(amount));
        body.put("creditNote", creditNote);
        body.put("reason", reason);
        return body;
    }

    private static void assertValue(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    /** A new member of a project. */
    private String member(String projectId, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, role);
        return id;
    }

    private JsonNode call(String user, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
