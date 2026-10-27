package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock-import S1: an unrepresentably small row reports its error without applying any valid prefix rows. */
@AutoConfigureMockMvc
class StockImportUnderflowInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0}, dryRun={1}: tiny exponent is a row error")
    @CsvSource({"quantity,true", "quantity,false", "packs,true", "packs,false"})
    void reportsQuantityAndPacksUnderflowWithoutPartialStock(String field, boolean dryRun) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(post("/projects"), Map.of("projectName", "Import underflow " + tag, "ownerId", DEMO_OWNER)))
            .path("projectId").asText();
        String code = "TINY-" + tag;
        ok(call(post("/items"), Map.of("projectId", project, "itemCode", code, "itemName", "Import material",
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", "N", "purchaseUnit", "bag", "purchaseUnitQty", 25)));
        assertNoStock(project);

        JsonNode result = ok(call(post("/inventories/import"), Map.of("projectId", project, "dryRun", dryRun,
            "rows", List.of(Map.of("itemCode", code, "location", "WH-GOOD", "quantity", "2"),
                Map.of("itemCode", code, "location", "WH-TINY", field, "1e-2147483647")))));

        assertEquals(dryRun, result.path("dryRun").asBoolean());
        assertFalse(result.path("applied").asBoolean());
        assertEquals(1, result.path("errors").asInt());
        assertEquals(2, result.path("rows").size());
        assertEquals("create", result.path("rows").get(0).path("action").asText());
        JsonNode rejected = result.path("rows").get(1);
        assertEquals("error", rejected.path("action").asText());
        assertEquals((field.equals("packs") ? "Packs" : "Quantity") + " is too small to store at four decimal places",
            rejected.path("message").asText());
        assertNoStock(project);
    }

    private void assertNoStock(String project) {
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory where project_id=?", Integer.class, project));
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?", Integer.class, project));
        assertEquals(0, jdbc.queryForObject("select count(*) from lot_master where project_id=?", Integer.class, project));
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.envelope().path("message").asText());
        assertTrue(reply.envelope().path("success").asBoolean());
        return reply.envelope().path("data");
    }

    private record Reply(int status, JsonNode envelope) {
    }
}
