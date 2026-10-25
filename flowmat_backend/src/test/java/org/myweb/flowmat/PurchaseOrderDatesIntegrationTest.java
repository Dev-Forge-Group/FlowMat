package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Moving the expected dates of an order being received (docs/domain/purchasing.md PO22-PO23), in a project of its own. */
@AutoConfigureMockMvc
class PurchaseOrderDatesIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anOrderBeingReceivedMovesItsDatesWithAReasonAndKeepsEachChange() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(DEMO_OWNER, post("/projects"), Map.of("projectName", "Dates " + tag, "ownerId", DEMO_OWNER), 200)
            .path("projectId").asText();
        String flour = item(project, "PD-FLOUR-" + tag);
        String salt = item(project, "PD-SALT-" + tag);
        JsonNode draft = call(DEMO_OWNER, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill Co",
            "expectedDate", "2041-09-10", "lines", List.of(Map.of("itemId", flour, "quantity", 10), Map.of("itemId", salt, "quantity", 5))), 200);
        String path = "/purchase-orders/" + draft.path("purchaseOrderId").asText();
        String flourLine = draft.path("lines").get(0).path("lineId").asText();
        String saltLine = draft.path("lines").get(1).path("lineId").asText();

        // A draft is edited whole instead; an order's dates move with a reason.
        call(DEMO_OWNER, post(path + "/expected-dates"), dates("2041-09-15", List.of(), "Supplier delayed"), 409);
        call(DEMO_OWNER, post(path + "/order"), null, 200);
        call(DEMO_OWNER, post(path + "/expected-dates"), dates("2041-09-15", List.of(), " "), 400);
        call(DEMO_OWNER, post(path + "/expected-dates"), dates("15/09/2041", List.of(), "Supplier delayed"), 400);
        call(DEMO_OWNER, post(path + "/expected-dates"), dates("2041-09-15", List.of(line("other", "2041-09-14")), "Supplier delayed"), 400);
        call(DEMO_OWNER, post(path + "/expected-dates"),
            dates("2041-09-15", List.of(line(flourLine, "2041-09-14"), line(flourLine, "2041-09-14")), "Supplier delayed"), 400);
        Map<String, Object> delay = dates("2041-09-15", List.of(line(flourLine, "2041-09-14")), "Supplier delayed");
        JsonNode moved = call(DEMO_OWNER, post(path + "/expected-dates"), delay, 200);
        assertEquals("2041-09-15", moved.path("expectedDate").asText());
        assertEquals("2041-09-14", moved.path("lines").get(0).path("expectedDate").asText());
        JsonNode changes = moved.path("dateChanges");
        assertEquals(2, changes.size(), changes.toString());
        assertTrue(changes.get(0).path("lineId").isNull(), changes.toString());
        assertEquals("2041-09-10", changes.get(0).path("previousDate").asText());
        assertEquals("2041-09-15", changes.get(0).path("expectedDate").asText());
        assertEquals("PD-FLOUR-" + tag, changes.get(1).path("itemCode").asText());
        assertTrue(changes.get(1).path("previousDate").isNull(), changes.toString());
        assertEquals("Supplier delayed", changes.get(1).path("reason").asText());
        assertEquals(DEMO_OWNER, changes.get(1).path("changedBy").asText());

        // The same dates again change nothing, so a repeat after a lost answer is harmless.
        assertEquals(2, call(DEMO_OWNER, post(path + "/expected-dates"), delay, 200).path("dateChanges").size());

        // A fully received line keeps its date; the order's own date can still be cleared.
        call(DEMO_OWNER, post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", saltLine, "quantity", 5))), 200);
        call(DEMO_OWNER, post(path + "/expected-dates"), dates("2041-09-15", List.of(line(saltLine, "2041-09-20")), "Late salt"), 400);
        JsonNode cleared = call(DEMO_OWNER, post(path + "/expected-dates"), dates(null, List.of(), "Supplier will call"), 200);
        assertTrue(cleared.path("expectedDate").isNull(), cleared.toString());
        assertEquals(3, cleared.path("dateChanges").size());
        assertEquals("2041-09-15", cleared.path("dateChanges").get(2).path("previousDate").asText());
        assertTrue(cleared.path("dateChanges").get(2).path("expectedDate").isNull(), cleared.toString());

        // Readers see the changes; only writers move dates.
        String viewer = member(project, "viewer");
        assertEquals(3, call(viewer, get(path), null, 200).path("dateChanges").size());
        call(viewer, post(path + "/expected-dates"), dates("2041-09-20", List.of(), "Viewer"), 403);
    }

    private static Map<String, Object> dates(String expectedDate, List<Map<String, Object>> lines, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedDate", expectedDate);
        body.put("lines", lines);
        body.put("reason", reason);
        return body;
    }

    private static Map<String, Object> line(String lineId, String expectedDate) {
        Map<String, Object> line = new HashMap<>();
        line.put("lineId", lineId);
        line.put("expectedDate", expectedDate);
        return line;
    }

    private String item(String projectId, String code) throws Exception {
        return call(DEMO_OWNER, post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg"), 200).path("itemId").asText();
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
