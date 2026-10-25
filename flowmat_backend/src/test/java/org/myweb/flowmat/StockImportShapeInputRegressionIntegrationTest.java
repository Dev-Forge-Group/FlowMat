package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class StockImportShapeInputRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void aNullRowIsABadRequestAndCannotSaveAnEarlierValidRow(boolean dryRun, boolean validRowFirst) throws Exception {
        Fixture fixture = fixture();
        ObjectNode body = importBody(fixture, dryRun);
        ArrayNode rows = body.putArray("rows");
        if (validRowFirst) {
            rows.add(row(fixture.code()));
        }
        rows.addNull();

        Reply response = call(post("/inventories/import"), body);

        assertNoStockWasWritten(fixture);
        assertEquals(400, response.status(), response.body().toString());
        assertTrue(response.body().path("message").asText().contains("rows"), response.body().toString());
        assertEquals(false, response.body().path("success").asBoolean(), response.body().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOrdinaryUnknownItemCodeStillUsesTheSuccessfulRowDiagnosticResponse(boolean dryRun) throws Exception {
        Fixture fixture = fixture();
        ObjectNode body = importBody(fixture, dryRun);
        body.putArray("rows").add(row(fixture.code() + "-MISSING"));

        Reply response = call(post("/inventories/import"), body);

        assertNoStockWasWritten(fixture);
        assertEquals(200, response.status(), response.body().toString());
        assertTrue(response.body().path("success").asBoolean(), response.body().toString());
        JsonNode result = response.body().path("data");
        assertEquals(dryRun, result.path("dryRun").asBoolean());
        assertEquals(false, result.path("applied").asBoolean(), result.toString());
        assertEquals(1, result.path("errors").asInt(), result.toString());
        assertEquals(1, result.path("rows").size());
        assertEquals("error", result.path("rows").get(0).path("action").asText());
        assertTrue(result.path("rows").get(0).path("message").asText().contains("No item has code"), result.toString());
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        JsonNode project = success(call(post("/projects"), Map.of("projectName", "Stock import shape " + tag,
            "ownerId", DEMO_OWNER)));
        String projectId = project.path("projectId").asText();
        String code = "IMPORT-SHAPE-" + tag;
        JsonNode item = success(call(post("/items"), Map.of("projectId", projectId, "itemCode", code,
            "itemName", "Stock import shape material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "N")));
        assertEquals("N", item.path("lotManageYn").asText(), "The valid prefix row must not need a LOT.");
        Fixture fixture = new Fixture(projectId, code);
        assertEquals(0, count("storage_location", projectId), "The valid prefix row uses a free-form location.");
        assertNoStockWasWritten(fixture);
        return fixture;
    }

    private ObjectNode importBody(Fixture fixture, boolean dryRun) {
        return mapper.createObjectNode().put("projectId", fixture.project()).put("dryRun", dryRun);
    }

    private ObjectNode row(String itemCode) {
        return mapper.createObjectNode().put("itemCode", itemCode).put("location", "SHAPE-WH").put("quantity", "1.25");
    }

    private void assertNoStockWasWritten(Fixture fixture) {
        assertEquals(0, count("inventory", fixture.project()));
        assertEquals(0, count("lot_master", fixture.project()));
        assertEquals(0, count("inventory_transaction", fixture.project()));
    }

    private int count(String table, String projectId) {
        return jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, projectId);
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    private static JsonNode success(Reply reply) {
        assertEquals(200, reply.status(), reply.body().toString());
        assertTrue(reply.body().path("success").asBoolean(), reply.body().toString());
        return reply.body().path("data");
    }

    private record Fixture(String project, String code) {
    }

    private record Reply(int status, JsonNode body) {
    }
}
