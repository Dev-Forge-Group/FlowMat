package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class StockImportIdentityRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void aSeparatorInTheLocationDoesNotTurnAnotherLotAndPlaceIntoADuplicate(boolean dryRun, boolean reverse)
        throws Exception {
        Fixture fixture = fixture();
        Map<String, String> first = row(fixture, "WH|a", "LOT-B", "1.2500", "2042-12-31");
        Map<String, String> second = row(fixture, "WH", "a|LOT-B", "2.5000", "2043-01-31");
        List<Map<String, String>> rows = reverse ? List.of(second, first) : List.of(first, second);

        JsonNode result = importRows(fixture, dryRun, rows);

        assertEquals(0, result.path("errors").asInt(), result.toString());
        assertEquals(2, result.path("created").asInt(), result.toString());
        assertEquals(0, result.path("received").asInt(), result.toString());
        assertEquals(2, result.path("newLots").asInt(), result.toString());
        assertEquals(dryRun, result.path("dryRun").asBoolean());
        assertEquals(!dryRun, result.path("applied").asBoolean(), result.toString());
        assertEquals(2, result.path("rows").size());
        assertEquals("create", result.path("rows").get(0).path("action").asText());
        assertEquals("create", result.path("rows").get(1).path("action").asText());
        if (dryRun) {
            assertNoStockWasWritten(fixture);
        } else {
            assertEquals(2, count("inventory", fixture.project()));
            assertEquals(2, count("lot_master", fixture.project()));
            assertEquals(2, count("inventory_transaction", fixture.project()));
            String firstLot = assertStored(fixture, "WH|a", "LOT-B", "1.25", "2042-12-31");
            String secondLot = assertStored(fixture, "WH", "a|LOT-B", "2.5", "2043-01-31");
            assertNotEquals(firstLot, secondLot, "Two different LOT numbers must register two distinct LOTs.");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theSamePlaceAndLotDifferingOnlyInLotLetterCaseRemainADuplicate(boolean dryRun) throws Exception {
        Fixture fixture = fixture();

        JsonNode result = importRows(fixture, dryRun, List.of(
            row(fixture, "WH|a", "LOT-B", "1.2500", "2042-12-31"),
            row(fixture, "WH|a", "lot-b", "2.5000", "2042-12-31")));

        assertEquals(1, result.path("errors").asInt(), result.toString());
        assertEquals(dryRun, result.path("dryRun").asBoolean());
        assertEquals(false, result.path("applied").asBoolean(), result.toString());
        assertEquals("create", result.path("rows").get(0).path("action").asText());
        assertEquals("error", result.path("rows").get(1).path("action").asText());
        assertTrue(result.path("rows").get(1).path("message").asText().contains("more than once"), result.toString());
        assertNoStockWasWritten(fixture);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = call(post("/projects"), Map.of("projectName", "Stock import identity " + tag,
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        assertEquals(0, count("storage_location", project), "These fixtures require free-form stock locations.");
        String code = "IMPORT-ID-" + tag;
        String item = call(post("/items"), Map.of("projectId", project, "itemCode", code,
            "itemName", "Import identity material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y"))
            .path("itemId").asText();
        Fixture fixture = new Fixture(project, item, code);
        assertNoStockWasWritten(fixture);
        return fixture;
    }

    private JsonNode importRows(Fixture fixture, boolean dryRun, List<Map<String, String>> rows) throws Exception {
        return call(post("/inventories/import"), Map.of("projectId", fixture.project(), "dryRun", dryRun, "rows", rows));
    }

    private static Map<String, String> row(Fixture fixture, String location, String lot, String quantity, String expiry) {
        return Map.of("itemCode", fixture.code(), "location", location, "lotNo", lot,
            "quantity", quantity, "expiryDate", expiry);
    }

    private String assertStored(Fixture fixture, String location, String lotNo, String quantity, String expiry) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("select i.inventory_id,i.item_id,i.lot_id,i.location,i.quantity,"
            + "i.reserved_quantity,i.available_quantity,l.lot_no,l.expiry_date from inventory i "
            + "join lot_master l on l.lot_id=i.lot_id where i.project_id=? and i.location=? and l.lot_no=?",
            fixture.project(), location, lotNo);
        String inventoryId = row.get("inventory_id").toString();
        String lotId = row.get("lot_id").toString();
        assertEquals(fixture.item(), row.get("item_id"));
        assertEquals(location, row.get("location"));
        assertEquals(lotNo, row.get("lot_no"));
        assertEquals(expiry, row.get("expiry_date").toString());
        assertAmount(quantity, (BigDecimal) row.get("quantity"));
        assertAmount("0", (BigDecimal) row.get("reserved_quantity"));
        assertAmount(quantity, (BigDecimal) row.get("available_quantity"));

        JsonNode stock = call(get("/inventories/" + inventoryId), null);
        assertEquals(location, stock.path("location").asText());
        assertEquals(lotId, stock.path("lotId").asText());
        assertAmount(quantity, stock.path("quantity").decimalValue());
        JsonNode lot = call(get("/lots/" + lotId), null);
        assertEquals(lotNo, lot.path("lotNo").asText());
        assertEquals(expiry, lot.path("expiryDate").asText());
        assertAmount(quantity, lot.path("quantityOnHand").decimalValue());

        Map<String, Object> movement = jdbc.queryForMap("select inventory_id,lot_id,transaction_type,quantity_delta,"
            + "quantity_after,reference_type,reference_id from inventory_transaction where project_id=? and inventory_id=?",
            fixture.project(), inventoryId);
        assertEquals(inventoryId, movement.get("inventory_id"));
        assertEquals(lotId, movement.get("lot_id"));
        assertEquals("receipt", movement.get("transaction_type"));
        assertAmount(quantity, (BigDecimal) movement.get("quantity_delta"));
        assertAmount(quantity, (BigDecimal) movement.get("quantity_after"));
        assertEquals("inventory", movement.get("reference_type"), "New stock records retain their ordinary creation receipt.");
        assertEquals(inventoryId, movement.get("reference_id"));
        return lotId;
    }

    private void assertNoStockWasWritten(Fixture fixture) throws Exception {
        assertEquals(0, count("inventory", fixture.project()));
        assertEquals(0, count("lot_master", fixture.project()));
        assertEquals(0, count("inventory_transaction", fixture.project()));
        assertEquals(0, call(get("/inventories").param("projectId", fixture.project()), null).size());
        assertEquals(0, call(get("/lots").param("projectId", fixture.project()), null).size());
    }

    private int count(String table, String projectId) {
        return jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, projectId);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), envelope.toString());
        assertTrue(envelope.path("success").asBoolean(), envelope.toString());
        return envelope.path("data");
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Fixture(String project, String item, String code) {
    }
}
